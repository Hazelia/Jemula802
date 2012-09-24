/*
 * 
 * This is Jemula.
 *
 *    Copyright (c) 2009 Stefan Mangold, Fabian Dreier, Stefan Schmid
 *    All rights reserved. Urheberrechtlich geschuetzt.
 *    
 *    Redistribution and use in source and binary forms, with or without modification,
 *    are permitted provided that the following conditions are met:
 *    
 *      Redistributions of source code must retain the above copyright notice,
 *      this list of conditions and the following disclaimer. 
 *    
 *      Redistributions in binary form must reproduce the above copyright notice,
 *      this list of conditions and the following disclaimer in the documentation and/or
 *      other materials provided with the distribution. 
 *    
 *      Neither the name of any affiliation of Stefan Mangold nor the names of its contributors
 *      may be used to endorse or promote products derived from this software without
 *      specific prior written permission. 
 *    
 *    THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY
 *    EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES
 *    OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED.
 *    IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT,
 *    INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING,
 *    BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA,
 *    OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY,
 *    WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 *    ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY
 *    OF SUCH DAMAGE.
 *    
 */

package layer2_80211Mac;

import gui.JE802Gui;
import java.util.ArrayList;
import java.util.Random;
import java.util.Vector;
import java.util.List;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;
import kernel.JEEvent;
import kernel.JEEventHandler;
import kernel.JEEventScheduler;
import kernel.JETime;
import kernel.JETimer;
import layer1_802Phy.JE802PhyMode;
import layer2_80211Mac.JE802_11Mpdu.JE80211MpduType;
import layer3_network.JE802IPPacket;

import org.w3c.dom.Element;
import org.w3c.dom.Node;

public final class JE802_11BackoffEntity extends JEEventHandler {

	private long overallTPCtr;
	private long specificTPCtr;

	private final JE802_11Mac theMac;

	private final JE802_11Vch theVch;

	private final JE802Gui theUniqueGui;

	private int theCW;

	private int theAC;

	private int dot11EDCACWmin;

	private int dot11EDCACWmax;

	private double dot11EDCAPF;

	private int dot11EDCAAIFSN;

	private JETime dot11EDCAMMSDULifeTime;

	private int dot11EDCATXOPLimit;

	private int theShortRetryCnt;

	private int theLongRetryCnt;

	private static int discardedCounter = 0;

	private int maxRetryCountShort;

	private int maxRetryCountLong;

	private Vector<JE802_11Mpdu> theQueue;

	private int theQueueSize;

	private JE802_11TimerNav theNavTimer;

	private JETimer theTxTimeoutTimer;

	private JETimer theInterFrameSpaceTimer;

	private JE802_11TimerBackoff theBackoffTimer;

	private JE802_11Mpdu theMpduRx;

	private JE802_11Mpdu theMpduData;

	private JE802_11Mpdu theMpduRts;

	private JE802_11Mpdu theMpduCtrl;

	private JETime theSlotTime;

	private JETime theSIFS;

	private JETime theAIFS;

	private int collisionCount = 0;

	private List<JE802PhyMode> phyList;

	private int noAckCount = 0;
	int debugCount = 0;

	// long previuosDiscarded=0;

	private enum txState {
		idle, txRts, txAck, txCts, txData
	}

	private txState theTxState;

	private int faultToleranceThreshold;

	private long previousDiscardedPacket = -1;
	private long previousReceivedAck = -1;
	private int successfullyTxedCount;

	public JE802_11BackoffEntity(JEEventScheduler aScheduler, Random aGenerator, JE802Gui aGui, Node aTopLevelNode,
			JE802_11Mac aMac, JE802_11Vch aVch) throws XPathExpressionException {

		super(aScheduler, aGenerator);
		theUniqueGui = aGui;
		this.theMac = aMac;
		this.theVch = aVch;

		overallTPCtr = 0;
		specificTPCtr = 0;

		this.phyList = new ArrayList<JE802PhyMode>();

		for (JE802PhyMode aPhyMode : phyList)
			aPhyMode.display_status();

		Element backoffElem = (Element) aTopLevelNode;
		if (backoffElem.getNodeName().equals("JE802BackoffEntity")) {
			this.theAC = new Integer(backoffElem.getAttribute("AC"));
			this.theQueue = new Vector<JE802_11Mpdu>();
			this.theQueueSize = new Integer(backoffElem.getAttribute("queuesize"));

			XPath xpath = XPathFactory.newInstance().newXPath();
			Element mibElem = (Element) xpath.evaluate("MIB802.11e", backoffElem, XPathConstants.NODE);
			if (mibElem != null) {
				this.dot11EDCACWmin = new Integer(mibElem.getAttribute("dot11EDCACWmin"));
				this.dot11EDCACWmax = new Integer(mibElem.getAttribute("dot11EDCACWmax"));
				this.dot11EDCAPF = new Double(mibElem.getAttribute("dot11EDCAPF"));
				this.dot11EDCAAIFSN = new Integer(mibElem.getAttribute("dot11EDCAAIFSN"));
				this.dot11EDCAMMSDULifeTime = new JETime(new Double(mibElem.getAttribute("dot11EDCAMSDULifetime")));
				this.dot11EDCATXOPLimit = new Integer(mibElem.getAttribute("dot11EDCATXOPLimit"));
			} else {
				this.error("Station " + this.theMac.getMacAddress() + " no MIB parameters found.");
			}

			this.theSIFS = this.theMac.getPhy().getSIFS();
			this.theSlotTime = this.theMac.getPhy().getSlotTime();
			this.theAIFS = this.theSIFS.plus(this.theSlotTime.times(this.dot11EDCAAIFSN));

			// contention window
			this.theCW = this.dot11EDCACWmin;

			// counters
			this.theShortRetryCnt = 0;
			this.theLongRetryCnt = 0;

			this.theNavTimer = new JE802_11TimerNav(theUniqueEventScheduler, theUniqueRandomGenerator, aGui, "nav_expired_ind",
					this.getHandlerId());
			this.theTxTimeoutTimer = new JETimer(theUniqueEventScheduler, theUniqueRandomGenerator, "tx_timeout_ind",
					this.getHandlerId());
			this.theInterFrameSpaceTimer = new JETimer(theUniqueEventScheduler, theUniqueRandomGenerator,
					"interframespace_expired_ind", this.getHandlerId());
			this.theBackoffTimer = new JE802_11TimerBackoff(theUniqueEventScheduler, theUniqueRandomGenerator, theUniqueGui,
					this.theMac.getMacAddress(), this.theAC, "backoff_expired_ind", this.getHandlerId(), this.theSlotTime, this);

			this.theTxState = txState.idle;
			this.updateBackoffTimer();

			this.theMpduRx = null;
			this.theMpduData = null;
			this.theMpduRts = null;
			this.theMpduCtrl = null;

		} else {
			this.warning("Station " + this.theMac.getMacAddress() + " messed up xml, dude.");
		}

		this.faultToleranceThreshold = 3;

	}

	@Override
	public void event_handler(JEEvent anEvent) {
		String anEventName = anEvent.getName();
		this.theLastRxEvent = anEvent;

		this.message("Station " + this.theMac.getMacAddress() + " on Channel " + this.theMac.getChannel() + " AC " + this.theAC
				+ " received event '" + anEventName + "'", 30);

		if (anEventName.contains("update_backoff_timer_req")) {
			this.updateBackoffTimer();
		} else if (anEventName.contains("MLME")) {
			this.event_MlmeRequests(anEvent);
		} else if (anEventName.equals("MSDUDeliv_req")) {
			this.event_MSDUDeliv_req(anEvent);
		} else if (anEventName.equals("MPDUDeliv_req")) {
			this.event_MPDUDeliv_req(anEvent);
		} else if (anEventName.equals("backoff_expired_ind")) {
			this.event_backoff_expired_ind(anEvent);
		} else if (anEventName.equals("interframespace_expired_ind")) {
			this.event_interframespace_expired_ind(anEvent);
		} else if (anEventName.equals("nav_expired_ind")) {
			this.event_nav_expired_ind(anEvent);
		} else if (anEventName.equals("tx_timeout_ind")) {
			this.event_tx_timeout_ind(anEvent);
		} else if (anEventName.equals("PHY_RxEnd_ind")) {
			this.event_PHY_RxEnd_ind(anEvent);
		} else if (anEventName.equals("PHY_SyncStart_ind")) {
			this.event_PHY_SyncStart_ind(anEvent);
		} else if (anEventName.equals("MPDUReceive_ind")) {
			this.event_MPDUReceive_ind(anEvent);
		} else if (anEventName.equals("virtual_collision_ind")) {
			this.event_virtual_collision_ind(anEvent);
		} else if (anEventName.equals("PHY_TxEnd_ind")) {
			this.event_PHY_TxEnd_ind(anEvent);
		} else if (anEventName.equals("decrease_phy_mode")) {
			this.event_reduce_phy_mode_ind(anEvent);
		} else if (anEventName.equals("increase_phy_mode")) {
			this.event_increase_phy_mode_ind(anEvent);
		} else {
			this.error("Station " + this.theMac.getMacAddress() + " undefined event '" + anEventName + "'");
		}
	}

	private void event_virtual_collision_ind(JEEvent anEvent) {
		this.theTxState = txState.idle;
		this.updateBackoffTimer();
	}

	private void event_reduce_phy_mode_ind(JEEvent anEvent) {
		// this.message("Current phy:"+this.theMac.getPhy().getCurrentPhyMode().toString());
		// Check for current Phy mode, and switch to the next lower one.
		if (this.theMac.getPhy().getCurrentPhyMode().toString().equals("54Mb/s")) {
			this.theMac.getPhy().setCurrentPhyMode("64QAM23");
		} else if (this.theMac.getPhy().getCurrentPhyMode().toString().equals("48Mb/s")) {
			this.theMac.getPhy().setCurrentPhyMode("16QAM34");
		} else if (this.theMac.getPhy().getCurrentPhyMode().toString().equals("36Mb/s")) {
			this.theMac.getPhy().setCurrentPhyMode("16QAM12");
		} else if (this.theMac.getPhy().getCurrentPhyMode().toString().equals("24Mb/s")) {
			this.theMac.getPhy().setCurrentPhyMode("QPSK34");
		} else if (this.theMac.getPhy().getCurrentPhyMode().toString().equals("18Mb/s")) {
			this.theMac.getPhy().setCurrentPhyMode("QPSK12");
		} else if (this.theMac.getPhy().getCurrentPhyMode().toString().equals("12Mb/s")) {
			this.theMac.getPhy().setCurrentPhyMode("BPSK34");
		} else if (this.theMac.getPhy().getCurrentPhyMode().toString().equals("9Mb/s")) {
			this.theMac.getPhy().setCurrentPhyMode("BPSK12");
		} else {
			// this.theMac.getPhy().setCurrentPhyMode("64QAM34"); //TODO:Change
			// this later, this is here only for testing purposes now!!!
			// do nothing, as already in lowest phy mode.
			// Possibly increase transmission power, to increase reachability
		}
		// this.message("Reducing to next lower PHY mode");
	}

	private void event_increase_phy_mode_ind(JEEvent anEvent) {
		// this.message("Current phy:"+this.theMac.getPhy().getCurrentPhyMode().toString());
		// Check for current Phy mode, and switch to the next lower one.
		if (this.theMac.getPhy().getCurrentPhyMode().toString().equals("6Mb/s")) {
			this.theMac.getPhy().setCurrentPhyMode("BPSK34");
		} else if (this.theMac.getPhy().getCurrentPhyMode().toString().equals("9Mb/s")) {
			this.theMac.getPhy().setCurrentPhyMode("QPSK12");
		} else if (this.theMac.getPhy().getCurrentPhyMode().toString().equals("12Mb/s")) {
			this.theMac.getPhy().setCurrentPhyMode("QPSK34");
		} else if (this.theMac.getPhy().getCurrentPhyMode().toString().equals("18Mb/s")) {
			this.theMac.getPhy().setCurrentPhyMode("16QAM12");
		} else if (this.theMac.getPhy().getCurrentPhyMode().toString().equals("24Mb/s")) {
			this.theMac.getPhy().setCurrentPhyMode("16QAM34");
		} else if (this.theMac.getPhy().getCurrentPhyMode().toString().equals("36Mb/s")) {
			this.theMac.getPhy().setCurrentPhyMode("64QAM23");
		} else if (this.theMac.getPhy().getCurrentPhyMode().toString().equals("48Mb/s")) {
			this.theMac.getPhy().setCurrentPhyMode("64QAM34");
		} else {
			// this.theMac.getPhy().setCurrentPhyMode("64QAM34"); //TODO:Change
			// this later, this is here only for testing purposes now!!!
			// do nothing, as already in highest phy mode.
			// possibly reduce transmission power of the station, to save power
		}
		// this.message("Reducing to next lower PHY mode");
	}

	private void event_PHY_TxEnd_ind(JEEvent anEvent) {
		if (this.theTxState.equals(txState.txAck) || this.theTxState.equals(txState.txCts)) {
			this.theTxState = txState.idle;
			this.theMpduCtrl = null;
			this.keep_going(); // now let us continue our own backoff, because
								// the backoff timer was paused before. we do
								// this even after CTS, so in case data is not
								// following, we just go ahead with our own
								// stuff.
		} else if (this.theMpduData != null && this.theMpduData.getDA() == this.theMac.getDot11BroadcastAddress()) {
			this.theMpduData = null;
			this.theTxState = txState.idle;
			this.theMpduCtrl = null;
			this.send(new JEEvent("broadcast_sent", this.theMac.getHandlerId(), anEvent.getScheduledTime()));
			this.keep_going();
		} else {
			// nothing
		}
	}

	private void increaseCw() {
		this.theCW = (int) Math.round((this.theCW + 1) * this.dot11EDCAPF) - 1;
		if (this.theCW < 0) {
			this.theCW = 0;
		} else if (this.theCW > this.dot11EDCACWmax) {
			this.theCW = this.dot11EDCACWmax;
		}
	}

	private void event_tx_timeout_ind(JEEvent anEvent) {
		// TODO: Add code here to check for failed transmissions.
		if (this.theTxState == txState.txRts) { // RTS was sent, but station did
												// not receive CTS frame
			this.theTxState = txState.idle;
			collisionCount++;

			// this.noAckCount++;
			// if(noAckCount==4){
			// noAckCount=0;
			// // this.send(new JEEvent("decrease_phy_mode_request",
			// this.getHandlerId(),this.getTime()),this.theMac.getMlme());
			// this.send("decrease_phy_mode_request",this.theMac.getMlme());
			// //noAckCount=0;
			// }

			this.retryRts();
		} else if (this.theTxState == txState.txData) { // data was sent, but
														// station did not
														// receive an ack frame
			this.theTxState = txState.idle;
			collisionCount++;

			// this.noAckCount++;
			// if(this.noAckCount==4){
			// this.noAckCount=0;
			// this.send("decrease_phy_mode_request",this.theMac.getMlme());
			// // this.send(new JEEvent("decrease_phy_mode_request",
			// this.getHandlerId(),this.getTime()),this.theMac.getMlme());
			// //this.theMac.getPhy().setCurrentPhyMode("16QAM12");
			// }

			this.retryData();
		} else if (this.theTxState == txState.txCts) { // TODO: not sure if this
														// should ever happen,
														// because there is no
														// timeout for control
														// frames
			this.theTxState = txState.idle;
			this.theMpduCtrl = null;
		} else if (this.theTxState == txState.txAck) { // TODO: not sure if this
														// should ever happen,
														// because there is no
														// timeout for control
														// frames
			this.theTxState = txState.idle;
			this.theMpduCtrl = null;
		}
		this.send("tx_timeout_ind", this.theVch);
		this.keep_going();
	}

	private void retryData() {

		// this.message("Data retry at :"+ this.getTime().toString());
		// this.noAckCount++;
		//
		// if(this.noAckCount>3){
		// this.noAckCount=0;
		// this.send(new JEEvent("decrease_phy_mode_request",
		// this.getHandlerId(),this.getTime()),this.theMac.getMlme());
		// }

		if (this.theMpduData == null) {
			this.error("Station " + this.theMac.getMacAddress() + " retryData: no pending RTS/DATA frame to transmit");
		}

		// this.message("Station "+this.theMac.getMacAddress()+" Retrying packet:"+theMpduData.getSeqNo()+" for attempt:"+this.noAckCount);
		if (this.theMpduData.getFrameBodySize() < this.theMac.getDot11RTSThreshold()) {
			this.theShortRetryCnt++; // station Short Retry Count

			// if(this.theShortRetryCnt>3){
			// this.message("More than 3 short retries. Switch to next lower PHY mode at:"+this.getTime());
			// this.theMac.getPhy().setCurrentPhyMode("BPSK12");
			// }
			// if(previuosDiscarded==this.theMpduData.getSeqNo()){
			// continuousDiscardCounter++;
			// }
			// else{
			// previuosDiscarded=this.theMpduData.getSeqNo();
			// }
			//
			// if(continuousDiscardCounter==3){
			// this.message("3 packets discarded in a row. Switching to lower Phy mode at:"+this.getTime());
			// this.theMac.getPhy().setCurrentPhyMode("BPSK12");
			// continuousDiscardCounter=0;
			// }

			if (this.theShortRetryCnt > this.theMac.getDot11ShortRetryLimit()) { // retransmissions
																					// exceeded
																					// the
																					// limit,
																					// now
																					// discard
																					// the
																					// frame

				JE802_11Mpdu data = this.theMpduData;
				this.theMpduData = null;
				this.theCW = this.dot11EDCACWmin; // reset contention window
				this.parameterlist.clear();
				this.parameterlist.add(data);
				this.parameterlist.add(false);
				this.parameterlist.add(this.theShortRetryCnt);
				setMaxRetryCountShort(Math.max(getMaxRetryCountShort(), theShortRetryCnt));
				this.discardedCounter++;
				this.theShortRetryCnt = 0;
				this.send(new JEEvent("MSDU_discarded_ind", this.theMac, theUniqueEventScheduler.now(), this.parameterlist));
			} else {
				this.increaseCw(); // increase the CW and ...
				this.deferForIFS(); // ... retry after the backoff
			}
		} else {
			this.theLongRetryCnt++; // station Long Retry Count
			// if(this.theLongRetryCnt>3){
			// this.message("More than 3 long retries. Switch to next lower PHY mode at:"+this.getTime());
			// this.theMac.getPhy().setCurrentPhyMode("BPSK12");
			// }
			if (this.theLongRetryCnt > this.theMac.getDot11LongRetryLimit()) {// retransmissions
																				// exceeded
																				// the
																				// limit,
																				// now
																				// discard
																				// the
																				// frame
				JE802_11Mpdu data = this.theMpduData;
				this.theMpduData = null;
				this.theCW = this.dot11EDCACWmin; // reset contention window
				this.parameterlist.clear();
				this.parameterlist.add(data);
				this.parameterlist.add(false);
				this.parameterlist.add(this.theLongRetryCnt);
				maxRetryCountLong = Math.max(maxRetryCountLong, theLongRetryCnt);
				this.discardedCounter++;
				this.theLongRetryCnt = 0;
				this.send(new JEEvent("MSDU_discarded_ind", this.theMac, theUniqueEventScheduler.now(), this.parameterlist));
			} else {
				this.increaseCw(); // increase the CW and ...
				this.deferForIFS(); // ... retry after the backoff
			}

		}
	}

	private void retryRts() {
		// this.message("RTS retry at :"+ this.getTime().toString());
		if ((this.theMpduData == null) | (this.theMpduRts == null)) {
			this.error("Station " + this.theMac.getMacAddress() + " retryRTS: no pending RTS/DATA frame to transmit");
		}

		// if(previuosDiscarded==this.theMpduData.getSeqNo()){
		// continuousDiscardCounter++;
		// }
		// else{
		// previuosDiscarded=this.theMpduData.getSeqNo();
		// }
		//
		// if(continuousDiscardCounter==3){
		// this.message("3 packets discarded in a row. Switching to lower Phy mode at:"+this.getTime());
		// this.theMac.getPhy().setCurrentPhyMode("BPSK12");
		// continuousDiscardCounter=0;
		// }

		// this.noAckCount++;
		// if(noAckCount>3){
		//
		// this.send(new JEEvent("decrease_phy_mode_request",
		// this.getHandlerId(),this.getTime()),this.theMac.getMlme());
		// noAckCount=0;
		// //
		// this.message("More than 3 ACKs not received.Use next lower Phy mode at:"+this.getTime());
		// // this.noAckCount=0;
		// // this.theMac.getPhy().setCurrentPhyMode("BPSK12");
		// }
		// this.message("Station "+this.theMac.getMacAddress()+" Retrying RTS packet:"+theMpduData.getSeqNo()+" for attempt:"+this.noAckCount);
		this.theShortRetryCnt++; // station Short Retry Count
		if (this.theShortRetryCnt > this.theMac.getDot11ShortRetryLimit()) { // retransmissions
																				// exceeded
																				// the
																				// limit,
																				// now
																				// discard
																				// the
																				// frame
			JE802_11Mpdu data = this.theMpduData;
			this.theMpduRts = null;
			this.theMpduData = null;
			maxRetryCountLong = Math.max(maxRetryCountLong, theLongRetryCnt);
			setMaxRetryCountShort(Math.max(getMaxRetryCountShort(), theShortRetryCnt));
			this.theShortRetryCnt = 0;
			this.theLongRetryCnt = 0;
			this.discardedCounter++;
			this.theCW = this.dot11EDCACWmin; // reset contention window
			this.parameterlist.clear();
			this.parameterlist.add(data);
			this.parameterlist.add(false);
			this.send(new JEEvent("MSDU_discarded_ind", this.theMac, theUniqueEventScheduler.now(), this.parameterlist));
		} else {
			this.increaseCw(); // increase the CW and ...
			this.deferForIFS(); // ... retry after the backoff
		}
	}

	private void event_backoff_expired_ind(JEEvent anEvent) {
		if (this.theMpduCtrl != null) {
			if (this.theBackoffTimer.is_idle() & this.theInterFrameSpaceTimer.is_idle()) {
				this.warning("Station " + this.theMac.getMacAddress() + " backoff error?");
				return;
			}
		}
		if (!this.checkAndTxRTS(false)) {
			if (!this.checkAndTxDATA(false)) {
				// no more rts and no more data.
				this.keep_going();
			}
		}
	}

	private void event_interframespace_expired_ind(JEEvent anEvent) {
		if ((this.theMpduCtrl == null) && (this.theMpduRts == null) && (this.theMpduData == null)) {
			this.keep_going();
		} else {
			if (this.checkAndTxCTRL()) {
			} else {
				if (this.theBackoffTimer.is_active()) {
					this.message("Station " + this.theMac.getMacAddress()
							+ " defer problem (possibly due to hidden station): backoff active while AIFS or any other IFS", 10);
				} else {
					if (this.checkAndTxRTS(true)) {
					} else {
						if (this.checkAndTxDATA(true)) {
						}
					}
				}
			}
		}
	}

	private void event_nav_expired_ind(JEEvent anEvent) {
		keep_going();
	}

	private void keep_going() {
		if (this.theBackoffTimer.is_paused() && !this.busy()) { // now let us
																// continue our
																// own backoff
			this.deferForIFS();
		} else { // or check next Mpdu
			this.nextMpdu();
		}
	}

	private void event_PHY_RxEnd_ind(JEEvent anEvent) { // received packet
														// completely or
														// collision occurred

		this.parameterlist = anEvent.getParameterList();

		if (this.parameterlist.elementAt(0) == null) { // bad luck: no packet at
														// all, just garbage
			this.theMpduRx = null;
			this.keep_going();
		} else { // we successfully received a packet, which is now given to us
					// as event parameter.
			this.theMpduRx = (JE802_11Mpdu) this.parameterlist.elementAt(0);
			if (this.theMpduRx.getDA() != theMac.getDot11BroadcastAddress()) {
				if ((this.theMpduRx.getDA() != this.theMac.getMacAddress()) || (this.theMpduRx.getAC() != this.theAC)) {
					this.theMpduRx = null;
					return; // this is all for this backoff entity. The frame is
							// not for us.
				}
			}
			JE80211MpduType type = this.theMpduRx.getType();
			switch (type) {
			case data:
				receiveData();
				break;
			case ack:
				discardedCounter = 0;
				this.message("Station " + this.theMac.getMacAddress() + " received ACK " + this.theMpduRx.getSeqNo()
						+ "from Station " + this.theMpduRx.getSA() + " on channel " + this.theMac.getChannel(), 10);
				receiveAck();
				break;
			case rts:
				this.message("Station " + this.theMac.getMacAddress() + " received RTS " + this.theMpduRx.getSeqNo()
						+ "from Station " + this.theMpduRx.getSA() + " on channel " + this.theMac.getChannel(), 10);
				receiveRts();
				break;
			case cts:
				this.message("Station " + this.theMac.getMacAddress() + " received CTS " + this.theMpduRx.getSeqNo()
						+ "from Station " + this.theMpduRx.getSA() + " on channel " + this.theMac.getChannel(), 10);
				receiveCts();
				break;
			default:
				this.error("Undefined MpduType");
			}
			this.theMpduRx = null;
		}
	}

	private void event_PHY_SyncStart_ind(JEEvent anEvent) { // phy starts
															// syncing. Lets
															// check the cca and
															// stop backoff if
															// needed.
		this.updateBackoffTimer();
	}

	private void updateBackoffTimer() {
		boolean busy = this.busy();
		if (this.theBackoffTimer.is_paused()) {
			if (busy) { // if busy then come back in 5 microseconds again and
						// see if channel is idle then
				this.send(new JEEvent("update_backoff_timer_req", this.getHandlerId(), theUniqueEventScheduler.now().plus(
						this.theSlotTime)));
			} else {
				this.deferForIFS();
				// this.theBackoffTimer.resume();
			}
		} else if (this.theBackoffTimer.is_active() && busy) { // react on
																// collision.
																// May even
																// happen
																// multiple
																// times right
																// away, for
																// each
																// colliding
																// frame.
			this.theBackoffTimer.pause();
		}
	}

	private void receiveRts() {
		if (this.theMpduRx.getDA() - this.theMac.getMacAddress() == 0) {
			if ((this.theMpduRx.getAC() - this.theAC) != 0) {
				this.error("Station " + this.theMac.getMacAddress() + " generating CTS for wrong AC: AC[theMpduRx]="
						+ this.theMpduRx.getAC() + " and AC[this]=" + this.theAC);
			}
			this.generateCts();
			if (this.theInterFrameSpaceTimer.is_active()) {
				this.theInterFrameSpaceTimer.stop();
			}
			this.deferForIFS();

		} else {
			// the received RTS-frame is for another station
		}
	}

	private void receiveAck() {

		this.noAckCount = 0;
		if (this.theTxState == txState.txData) {
			if (this.theMpduData == null) {
				this.error("Station " + this.theMac.getMacAddress() + " receiveAck: station has no data frame");
			}
			JE802_11Mpdu receivedData = this.theMpduData; // store for
															// forwarding
			this.parameterlist.clear();
			this.parameterlist.add(receivedData);

			if (this.getMac().getMlme().isARFEnabled()) {
				if (receivedData.getSeqNo() == this.previousReceivedAck + 1) {
					this.successfullyTxedCount++;
					// this.message("Packets successfully delivered:"+this.successfullyTxedCount);
					this.previousReceivedAck = receivedData.getSeqNo();

					if (this.successfullyTxedCount == 10) {
						this.send(new JEEvent("increase_phy_mode_request", this.theMac.getMlme(), this.getTime()));
						this.successfullyTxedCount = 0;
					}
				}
			}

			if (this.theMpduData.getFrameBodySize() < this.theMac.getDot11RTSThreshold()) {
				this.parameterlist.add(this.theShortRetryCnt);
				setMaxRetryCountShort(Math.max(getMaxRetryCountShort(), theShortRetryCnt));
				this.theShortRetryCnt = 0;
			} else {
				this.parameterlist.add(this.theLongRetryCnt);
				maxRetryCountLong = Math.max(maxRetryCountLong, theLongRetryCnt);
				this.theLongRetryCnt = 0;
			}

			this.theMpduData = null;
			this.theNavTimer.stop();
			this.theCW = this.dot11EDCACWmin; // reset contention window
			this.theTxState = txState.idle;
			this.theTxTimeoutTimer.stop();
			if (this.theBackoffTimer.is_active()) {
				this.warning("Station " + this.theMac.getMacAddress() + " receiveAck: backoff timer is active.");
			}
			// report successful delivery to MAC
			this.send(new JEEvent("MSDU_delivered_ind", this.theMac.getHandlerId(), theUniqueEventScheduler.now(),
					this.parameterlist));
			this.deferForIFS();
		} else {
			this.keep_going();
		}
	}

	private void receiveCts() {

		if (this.theTxState != txState.txRts) { // do nothing in case the
												// station did not transmit rts
												// frame
		} else {
			this.noAckCount = 0; // received CTS, so the packet is acknowledged,
									// now restart the counter.
			if (this.theMpduRts == null) {
				this.message("Station " + this.theMac.getMacAddress() + " receiveCts: station has no pending rts frame", 30);
			}
			this.theMpduRts = null; // we sent the rts, and received cts. So
									// let's assume rts has done its job.
			if (this.theMpduData == null) {
				this.message("Station " + this.theMac.getMacAddress() + " receiveCts: station has no pending data frame", 30);
			}
			this.theTxTimeoutTimer.stop();
			this.theShortRetryCnt = 0;
			setMaxRetryCountShort(Math.max(theShortRetryCnt, getMaxRetryCountShort()));
			this.theCW = this.dot11EDCACWmin; // reset contention window
			this.deferForIFS();
		}
	}

	private void receiveData() {
		if (this.theMpduRx.getDA() == this.theMac.getMacAddress()) {
			if (this.theMpduRx.getAC() != this.theAC) {
				this.error("Station " + this.theMac.getMacAddress()
						+ " this backoff entity generates an ACK for a data frame of different AC.");
			}
			this.theMpduCtrl = null;
			this.theTxTimeoutTimer.stop();
			// send received packet back to its source, for evaluation:
			this.parameterlist.clear();
			this.parameterlist.add(this.theMpduRx.clone()); // make a copy since
															// arrival time will
															// be changed until
															// mpdu gets
															// evaluated
			this.theMpduRx.setLastArrivalTime(theUniqueEventScheduler.now());
			this.parameterlist.add(theUniqueEventScheduler.now());
			this.parameterlist.add(this.theAC);
			// this.send(new JEEvent("hop_evaluation",
			// this.theMpduRx.getSourceHandler(), theUniqueEventScheduler.now(),
			// this.parameterlist));
			// if there are more addresses in the list, the packet is forwarded,
			// otherwise it reached its final destination
			if (this.theMpduRx.getHopAddresses() != null && !this.theMpduRx.getHopAddresses().isEmpty()) {
				this.send(new JEEvent("packet_forward", this.theMac, theUniqueEventScheduler.now(), this.parameterlist));
			} else {
				this.send(new JEEvent("packet_exiting_system_ind", this.theMac.getHandlerId(), theUniqueEventScheduler.now(),
						this.parameterlist));
			}
			this.theMpduRx.setLastArrivalTime(theUniqueEventScheduler.now());
			this.generateAck(); // generate an ACK
			this.deferForIFS();
			// the packet is a broadcast packet
		} else if (this.theMpduRx.getDA() == theMac.getDot11BroadcastAddress()) {
			this.theMpduCtrl = null;
			this.theTxTimeoutTimer.stop();
			// packet was send by ourselves
			if (this.theMpduRx.getSA() != this.theMac.getMacAddress()) {
				this.parameterlist.clear();
				this.parameterlist.add(this.theMpduRx.clone());
				this.parameterlist.add(theUniqueEventScheduler.now());
				this.parameterlist.add(this.theAC);
				this.send(new JEEvent("packet_exiting_system_ind", this.theMac.getHandlerId(), theUniqueEventScheduler.now(),
						this.parameterlist));
			}
			this.deferForIFS();
		} else {
			this.error("Station " + this.theMac.getMacAddress()
					+ " the received MPDU should be ours, but it is for another station. Not good.");
		}
	}

	private void event_MPDUReceive_ind(JEEvent anEvent) {
		this.parameterlist = anEvent.getParameterList();
		Integer aMacId = new Integer((Integer) this.parameterlist.elementAt(0));
		Integer anAc = new Integer((Integer) this.parameterlist.elementAt(1));
		JETime aNav = new JETime((JETime) this.parameterlist.elementAt(2));

		if (this.theBackoffTimer.is_active()) { // the classical case: while
												// down-counting, another
												// station initiated a frame
												// exchange
			this.theBackoffTimer.pause();
		}
		this.theMpduRx = new JE802_11Mpdu();
		this.theMpduRx.setDA(aMacId); // destination MAC address
		this.theMpduRx.setAC(anAc); // access category
		this.theMpduRx.setNAV(aNav); // NAV value
		if (!(aMacId == this.theMac.getMacAddress() && anAc == this.theAC) || aMacId == theMac.getDot11BroadcastAddress()) { // we
																																// only
																																// set
																																// the
																																// nav
																																// timer
																																// if
																																// we
																																// are
																																// NOT
																																// the
																																// destination
			this.theNavTimer.start(aNav, this.theMac.getMacAddress(), this.theAC, this.theMac.getChannel());
		}
	}

	@SuppressWarnings("unchecked")
	private void event_MSDUDeliv_req(JEEvent anEvent) {
		this.parameterlist = anEvent.getParameterList();
		JE802IPPacket packet = (JE802IPPacket) this.parameterlist.elementAt(3);
		int size = packet.getLength();
		long seqno = (Long) anEvent.getParameterList().get(4); // MAC Seq No,
																// not TCP
		int DA = ((JE802HopInfo) this.parameterlist.elementAt(0)).getAddress();
		ArrayList<JE802HopInfo> hopAdresses = (ArrayList<JE802HopInfo>) this.parameterlist.elementAt(2);
		int sourceHandler = (Integer) anEvent.getParameterList().get(5);
		int headersize = this.theMac.getDot11MacHeaderDATA_byte();
		JE802_11Mpdu aMpdu = new JE802_11Mpdu(this.theMac.getMacAddress(), DA, JE80211MpduType.data, seqno, size, headersize,
				this.theAC, sourceHandler, hopAdresses, theUniqueEventScheduler.now(), packet);

		// no queue handling needed here: this is done in event_MPDUDeliv_req
		// ... . Just request MPDUDeliv_req and thats it.
		this.parameterlist.clear();
		this.parameterlist.add(aMpdu);
		this.send(new JEEvent("MPDUDeliv_req", this, theUniqueEventScheduler.now(), this.parameterlist));
	}

	private void event_MPDUDeliv_req(JEEvent anEvent) {

		specificTPCtr++;
		overallTPCtr++;

		// TODO: Better place to check sequence no.

		this.parameterlist = anEvent.getParameterList();
		JE802_11Mpdu aMpdu = (JE802_11Mpdu) this.parameterlist.elementAt(0);
		aMpdu.setPhyMode(this.theMac.getPhy().getCurrentPhyMode());
		aMpdu.setTxTime(this.calcTxTime(aMpdu)); // here we actually
		// store the txtime in the Mpdu as well now set the NAV to
		// FRAMEDURATION(the tx time)-SYNCDUR (usually 20us)+SIFS+ACK frame:
		aMpdu.setNAV(this
				.calcTxTime(aMpdu)
				.minus(this.theMac.getPhy().getPLCPHeaderDuration())
				.plus(this.theSIFS.plus(this.calcTxTime(0, this.theMac.getDot11MacHeaderACK_byte(), this.theMac.getPhy()
						.getBasicPhyMode(aMpdu.getPhyMode())))));
		if ((this.theMpduData == null) && (!this.theInterFrameSpaceTimer.is_active())) {
			this.theMpduData = aMpdu;
			this.theMpduData.setType(JE80211MpduType.data);
			this.theMpduData.setSA(this.theMac.getMacAddress());
			this.theMpduData.setTxTime(this.calcTxTime(this.theMpduData));
			this.theMpduData.setNAV(this
					.calcTxTime(aMpdu)
					.minus(this.theMac.getPhy().getPLCPHeaderDuration())
					.plus(this.theSIFS.plus(this.calcTxTime(0, this.theMac.getDot11MacHeaderACK_byte(), this.theMac.getPhy()
							.getBasicPhyMode(aMpdu.getPhyMode())))));
			this.generateRts();
			boolean busy = this.busy();
			if (!this.theBackoffTimer.is_active()) {
				if (!busy) {
					if (!this.theInterFrameSpaceTimer.is_active()) {
						this.deferForIFS();
					} else {
						this.error("Station " + this.theMac.getMacAddress() + " already deferring");
					}
				} else {
					if (this.theBackoffTimer.is_idle()) {
						this.theBackoffTimer.start(this.theCW, busy); // new
																		// backoff
						this.updateBackoffTimer();
					}
				}
			} else {
				// we have to wait until backoff expires. Happens often during
				// post backoff.
			}
		} else { // queue the MPDU or discard if queue full - in this case
					// inform TrafficGen
			if (this.theQueue.size() >= this.theQueueSize) {
				this.parameterlist.clear();
				this.parameterlist.add(this.theMpduData);
				this.parameterlist.add(true);
				this.send(new JEEvent("MSDU_discarded_ind", this.theMac, theUniqueEventScheduler.now(), this.parameterlist));
			} else {
				this.theQueue.add(aMpdu);
			}
		}
		// this.message("Trying packet no:"+aMpdu.getSeqNo());
	}

	private void nextMpdu() {
		if (!this.theQueue.isEmpty()) {
			if (this.theMpduData == null) {
				this.theLongRetryCnt = 0;
				this.theShortRetryCnt = 0;
				JE802_11Mpdu aMpdu = this.theQueue.remove(0); // we pull out a
																// new mpdu from
																// the queue
				this.parameterlist.clear();
				this.parameterlist.add(aMpdu);
				this.send(new JEEvent("MPDUDeliv_req", this, theUniqueEventScheduler.now(), this.parameterlist));
			} else { // backoff was stopped before. Resume it by starting IFS
						// defer.
				this.deferForIFS();
			}
		} else {
			if (this.theMpduData != null) {
				this.deferForIFS();
			}
			// the queue is empty. So we don't have anything to do - well this
			// is not entirely true:
			// If we are using the "saturation" traffic model, now is the time
			// to ask the traffic
			// gen for another MPDU. Why? Because we ALWAYS have to pump data.
			// So let us ask for another MPDU:
			this.parameterlist.clear();
			this.parameterlist.add(this.theAC);
			this.send(new JEEvent("empty_queue_ind", this.theMac, theUniqueEventScheduler.now(), this.parameterlist));

		}
	}

	private void event_MlmeRequests(JEEvent anEvent) {
		String anEventName = anEvent.getName();
		if (anEventName.equals("undefinded")) { // nothing defined in MLME so
												// far
		} else {
			this.error("Station " + this.theMac.getMacAddress() + " undefined MLME request event '" + anEventName + "'");
		}
	}

	private void tx(JE802_11Mpdu aTxPdu, JETime aTimeout) {

		if (this.theMac.getMlme().isARFEnabled()) {
			aTxPdu.setPhyMode(theMac.getPhy().getCurrentPhyMode());
			if (aTxPdu.getSeqNo() == previousDiscardedPacket) {
				// this.noAckCount++;
				if (noAckCount == 3) {
					this.send(new JEEvent("decrease_phy_mode_request", this.theMac.getMlme(), this.getTime()));
					noAckCount = 0;
					// noAckCount=0;
				}
				this.noAckCount++;
			} else {
				previousDiscardedPacket = aTxPdu.getSeqNo();
			}
		}

		this.parameterlist.clear();
		this.parameterlist.add(aTxPdu);
		this.send(new JEEvent("txattempt_req", this.theVch, theUniqueEventScheduler.now(), this.parameterlist));
		if (aTxPdu.isData() || aTxPdu.isRts()) {
			// only RTS and DATA know the timeout, because they expect something
			// back: CTS or ACK, resp.
			this.theTxTimeoutTimer.start(aTimeout);
		}
	}

	private void deferForIFS() {
		if (!this.theInterFrameSpaceTimer.is_active()) { // ignore during
															// interframe space
															// defering interval
			if (this.theMpduCtrl != null) { // CTS or ACK required
				this.theInterFrameSpaceTimer.start(this.theSIFS);
			} else if (this.theMpduRts != null) {
				if (!this.theBackoffTimer.is_active()) {
					this.theInterFrameSpaceTimer.start(this.theAIFS);
				}
			} else if (this.theMpduData != null) {
				if (!this.theBackoffTimer.is_active()) {
					if (this.theTxState == txState.txRts) {
						this.theInterFrameSpaceTimer.start(this.theSIFS); // we
																			// sent
																			// RTS
																			// before
																			// and
																			// just
																			// received
																			// CTS.
																			// Now
																			// SIFS
																			// is
																			// used
																			// before
																			// DATA,
																			// not
																			// AIFS
					} else {
						this.theInterFrameSpaceTimer.start(this.theAIFS);
					}
				} else { // backoff is busy. Do nothing though data is pending.
					this.message("Station " + this.theMac.getMacAddress()
							+ " deferForIFS: doing nothing though data is pending: backoff timer already busy.", 10);
				}
			} else { // the transmission was successful.
				this.theTxState = txState.idle;
				this.nextMpdu(); // now check for next MPDU
			}
		}
	}

	private boolean busy() { // returns true if NAV or medium is busy, or
								// interframe space is ongoing (SIFS, AIFS)

		if (this.theNavTimer.getExpiryTime().isLaterThan(theUniqueEventScheduler.now()) || this.theMac.getPhy().isCcaBusy()
				|| this.theInterFrameSpaceTimer.is_active()) {
			// theUniqueGui.addLine(theUniqueEventScheduler.now(),
			// this.theMac.getMacAddress(), this.theAC-1, "red",
			// this.theMac.getChannel());
			return true;
		} else {
			// theUniqueGui.addLine(theUniqueEventScheduler.now(),
			// this.theMac.getMacAddress(), this.theAC-1, "green",
			// this.theMac.getChannel());
			return false;
		}
	}

	private void generateRts() {
		if (this.theMpduData != null) {
			if (this.theMpduRts == null) {
				if ((this.theMpduData.getFrameBodySize() < this.theMac.getDot11RTSThreshold())) {
					this.theMpduRts = null;
				} else {
					Integer headersize = this.theMac.getDot11MacHeaderRTS_byte();
					this.theMpduRts = new JE802_11Mpdu(this.theMpduData.getSA(), this.theMpduData.getDA(), JE80211MpduType.rts,
							0, 0, headersize, this.theAC, -1, null, null, null);
					this.theMpduRts.setPhyMode(this.theMac.getPhy().getBasicPhyMode(this.theMpduData.getPhyMode()));
					// calculate transmission time, with RTS framebody size 0
					this.theMpduRts.setTxTime(this.calcTxTime(0, this.theMac.getDot11MacHeaderRTS_byte(), this.theMac.getPhy()
							.getBasicPhyMode(this.theMpduData.getPhyMode())));
					// set seqno
					this.theMpduRts.setSeqNo(this.theMpduData.getSeqNo());
					// calculate RTS NAV duration field
					this.theMpduRts.setNAV(this.theMpduRts
							.getTxTime()
							.minus(this.theMac.getPhy().getPLCPHeaderDuration())
							.plus(this.theSIFS.plus(this.calcTxTime(0, this.theMac.getDot11MacHeaderCTS_byte(),
									this.theMac.getPhy().getBasicPhyMode(this.theMpduData.getPhyMode())).plus(
									this.theSIFS
											.plus(this.theMpduData.getTxTime())
											.plus(this.theSIFS)
											.plus(this.calcTxTime(0, this.theMac.getDot11MacHeaderACK_byte(), this.theMac
													.getPhy().getBasicPhyMode(this.theMpduData.getPhyMode())))))));
				}
			} else {
				this.error("Station " + this.theMac.getMacAddress() + " backoff entity has ongoing RTS frame.");
			}
		} else {
			this.error("Station " + this.theMac.getMacAddress() + " backoff entity has ongoing MPDU.");
		}
	}

	private void generateCts() {
		int aDA = this.theMpduRx.getSA();
		JETime anRtsNav = this.theMpduRx.getNav();
		if (this.theMpduCtrl == null) {
			int headersize = this.theMac.getDot11MacHeaderCTS_byte();
			this.theMpduCtrl = new JE802_11Mpdu(0, aDA, JE80211MpduType.cts, 0, 0, headersize, this.theAC, -1, null, null, null);
			this.theMpduCtrl.setSA(this.theMac.getMacAddress());
			// calculate CTS transmission time
			this.theMpduCtrl.setPhyMode(this.theMac.getPhy().getCurrentPhyMode());
			this.theMpduCtrl.setTxTime(this.calcTxTime(0, this.theMac.getDot11MacHeaderCTS_byte(), this.theMac.getPhy()
					.getCurrentPhyMode()));
			// set seqno
			this.theMpduCtrl.setSeqNo(this.theMpduRx.getSeqNo());
			// calculate CTS's nav value:
			this.theMpduCtrl.setNAV(anRtsNav.minus(this.theSIFS).minus(this.theMpduCtrl.getTxTime())
					.minus(this.theMac.getPhy().getPLCPHeaderDuration()));
		} else {
			this.message("Station " + this.theMac.getMacAddress() + "generateCTS: backoff entity has pending CTS/ACK frame", 10); // this
																																	// can
																																	// happen
																																	// in
																																	// hidden
																																	// node
																																	// scenarios
		}
	}

	private void generateAck() {
		if (this.theMpduCtrl == null) {
			int headersize = this.theMac.getDot11MacHeaderACK_byte();
			this.theMpduCtrl = new JE802_11Mpdu(0, this.theMpduRx.getSA(), JE80211MpduType.ack, 0, 0, headersize, this.theAC, -1,
					null, null, null);
			this.theMpduCtrl.setTxTime(this.calcTxTime(0, this.theMac.getDot11MacHeaderACK_byte(), this.theMac.getPhy()
					.getBasicPhyMode(this.theMpduRx.getPhyMode())));
			this.theMpduCtrl.setNAV(this.theMpduCtrl.getTxTime().minus(this.theMac.getPhy().getPLCPHeaderDuration()));
			this.theMpduCtrl.setSA(this.theMac.getMacAddress());
			this.theMpduCtrl.setPhyMode(this.theMac.getPhy().getBasicPhyMode(this.theMpduRx.getPhyMode()));
			// set seqno
			this.theMpduCtrl.setSeqNo(this.theMpduRx.getSeqNo());
			this.message("Station " + this.theMac.getMacAddress() + " sent ACK " + this.theMpduCtrl.getSeqNo() + " to Station "
					+ this.theMpduCtrl.getDA() + " on channel " + this.theMac.getChannel(), 10);
		} else {
			this.error("Station " + this.theMac.getMacAddress() + " generateACK: backoff entity has pending CTS/ACK frame");
		}
	}

	private boolean checkAndTxRTS(boolean IfsExpired) {
		if (this.theBackoffTimer.is_active()) {
			this.warning("Station " + this.theMac.getMacAddress() + " backoff still busy"); // this
																							// should
																							// not
																							// happen.
			return false;
		}
		if (this.theMpduRts == null) {
			return false; // no RTS to send
		}
		if (this.theMpduRts.isRts()) {
			if (IfsExpired) { // we are now at the end of the IFS
				boolean busy = this.busy();
				if (this.theBackoffTimer.is_paused() && !busy) {
					this.theBackoffTimer.resume(); // continue downcounting
				}
				if (this.theBackoffTimer.is_idle()) {
					// new backoff
					this.theBackoffTimer.start(this.theCW, busy);
					this.updateBackoffTimer();
				}
				return true;
			} // we are now at the end of the backoff
			if (!this.busy()) {
				this.theTxState = txState.txRts;
				this.updateBackoffTimer();
				JETime aTimeout = new JETime(this.calcTxTime(0, this.theMac.getDot11MacHeaderRTS_byte(),
						this.theMpduRts.getPhyMode()));
				aTimeout = aTimeout.plus(this.theSIFS);
				aTimeout = aTimeout
						.plus(this.calcTxTime(0, this.theMac.getDot11MacHeaderCTS_byte(), this.theMpduRts.getPhyMode()));
				aTimeout = aTimeout.plus(this.theSlotTime);
				this.tx(this.theMpduRts, aTimeout);
				return true; // RTS was sent (if no error occurred. But then we
								// are in trouble anyway.)
			} else {
				return false;
			}
		} else {
			this.error("Station " + this.theMac.getMacAddress() + " has Mpdu of wrong subtype, expected " + JE80211MpduType.rts);
			return false;
		}
	}

	private boolean checkAndTxCTRL() {
		if (this.theMpduCtrl == null) {
			return false; // no CTS or ACK to send
		}
		if (this.theMpduCtrl.isCts()) {
			this.theTxState = txState.txCts;
		} else if (this.theMpduCtrl.isAck()) {
			this.theTxState = txState.txAck;
		} else {
			this.error("Station " + this.theMac.getMacAddress() + " has Mpdu of wrong subtype, expected " + JE80211MpduType.ack
					+ " or " + JE80211MpduType.cts);
		}
		this.tx(this.theMpduCtrl, /* no timeout for CTS or ACK: */null);
		this.theMpduCtrl = null;
		return true;
	}

	private boolean checkAndTxDATA(boolean IfsExpired) {
		if (this.theBackoffTimer.is_active()) {
			this.warning("Station " + this.theMac.getMacAddress() + " backoff still busy");
			return false;
		}
		if (this.theMpduData == null) {
			return false; // no DATA to send
		}
		if (this.theMpduData.isData()) {
			if (IfsExpired && !this.theTxState.equals(txState.txRts)) {
				boolean busy = this.busy();
				if (this.theBackoffTimer.is_paused() && !busy) {
					this.theBackoffTimer.resume(); // continue down counting
				}
				if (this.theBackoffTimer.is_idle()) { // new backoff
					this.theBackoffTimer.start(this.theCW, busy);
					this.updateBackoffTimer();
				}
				return true;
			}
			if (!this.busy()) {
				this.theTxState = txState.txData;
				this.updateBackoffTimer();
				JETime aTimeout = new JETime(this.theMpduData.getTxTime());
				aTimeout = aTimeout.plus(this.theSIFS);
				aTimeout = aTimeout.plus(this.calcTxTime(0, this.theMac.getDot11MacHeaderACK_byte(), this.theMac.getPhy()
						.getBasicPhyMode(this.theMpduData.getPhyMode())));
				aTimeout = aTimeout.plus(this.theSlotTime);
				this.tx(this.theMpduData, aTimeout);
				return true;
			} else {
				return false;
			}
		} else {
			this.error("Station " + this.theMac.getMacAddress() + " has Mpdu of wrong subtype, expected " + JE80211MpduType.data);
			return false;
		}
	}

	private JETime calcTxTime(int framebodysize, int headersize, JE802PhyMode aPhyMode) {

		// first the preamble:
		JETime aTxTime = this.theMac.getPhy().getPLCPPreamble();
		// now add the phy header:
		aTxTime = aTxTime.plus(this.theMac.getPhy().getPLCPHeaderWithoutServiceField());
		// now calc the payload duration incl MAC Header in byte:
		int framesize = framebodysize + headersize + this.theMac.getDot11MacFCS_byte();
		if (this.theMac.isDot11WepEncr()) {
			framesize = framesize + 8 * 32;// this.theMac.get("MACCONFIG.WEP_byte");
		}
		// now calc the duration of this payload:
		int aNumOfPayload_bit = this.theMac.getPhy().getPLCPServiceField_bit() + framesize * 8;
		int aNumOfSymbols = (aNumOfPayload_bit + aPhyMode.getBitsPerSymbol() - 1) / aPhyMode.getBitsPerSymbol();
		JETime aPayloadDuration = this.theMac.getPhy().getSymbolDuration().times(aNumOfSymbols);
		// now calculate the final duration and return it:
		aTxTime = aTxTime.plus(aPayloadDuration);
		return aTxTime;
	}

	private JETime calcTxTime(JE802_11Mpdu aMpdu) {
		int framebodysize = aMpdu.getFrameBodySize();
		int headersize = aMpdu.getHeaderSize();
		JE802PhyMode aPhyMode = aMpdu.getPhyMode();
		return this.calcTxTime(framebodysize, headersize, aPhyMode);
	}

	public Integer getAC() {
		return theAC;
	}

	public JETime getAIFS() {
		return theAIFS;
	}

	public int getDot11EDCAAIFSN() {
		return dot11EDCAAIFSN;
	}

	public int getDot11EDCACWmax() {
		return dot11EDCACWmax;
	}

	public int getDot11EDCACWmin() {
		return dot11EDCACWmin;
	}

	public JETime getDot11EDCAMMSDULifeTime() {
		return dot11EDCAMMSDULifeTime;
	}

	public int getDot11EDCATXOPLimit() {
		return dot11EDCATXOPLimit;
	}

	public double getDot11EDCAPF() {
		return dot11EDCAPF;
	}

	public int getCollisionCount() {
		return collisionCount;
	}

	public int getQueueSize() {
		return theQueueSize;
	}

	public int getCurrentQueueSize() {
		return this.theQueue.size();
	}

	public JE802_11Mac getMac() {
		return theMac;
	}

	/**
	 * @param maxRetryCountShort
	 *            the maxRetryCountShort to set
	 */
	public void setMaxRetryCountShort(int maxRetryCountShort) {
		this.maxRetryCountShort = maxRetryCountShort;
	}

	/** @return the maxRetryCountShort */
	public int getMaxRetryCountShort() {
		return maxRetryCountShort;
	}

	/**
	 * @param maxRetryCountLong
	 *            the maxRetryCountLong to set
	 */
	public void setMaxRetryCountLong(int maxRetryCountLong) {
		this.maxRetryCountLong = maxRetryCountLong;
	}

	/** @return the maxRetryCountLong */
	public int getMaxRetryCountLong() {
		return maxRetryCountLong;
	}

	public void setAC(Integer theAC) {
		this.theAC = theAC;
	}

	public void setDot11EDCAAIFSN(Integer dot11edcaaifsn) {
		if (dot11edcaaifsn < 1) {
			this.warning("Station " + this.theMac.getMacAddress() + " AIFSN < 1: " + dot11edcaaifsn);
			dot11edcaaifsn = 1;
		}
		dot11EDCAAIFSN = dot11edcaaifsn;
	}

	public void setDot11EDCACWmax(Integer dot11edcacWmax) {
		dot11EDCACWmax = dot11edcacWmax;
	}

	public void setDot11EDCACWmin(Integer dot11edcacWmin) {
		if (dot11edcacWmin < 1) {
			this.warning("Station " + this.theMac.getMacAddress() + " CWmin < 1: " + dot11edcacWmin);
			dot11edcacWmin = 1;
		}
		dot11EDCACWmin = dot11edcacWmin;
	}

	public void setDot11EDCAPF(Double dot11edcapf) {
		dot11EDCAPF = dot11edcapf;
	}

	public void setDot11EDCATXOPLimit(Integer dot11edcatxopLimit) {
		dot11EDCATXOPLimit = dot11edcatxopLimit;
	}

	public void setDot11EDCAMMSDULifeTime(JETime dot11edcammsduLifeTime) {
		dot11EDCAMMSDULifeTime = dot11edcammsduLifeTime;
	}

	@Override
	public String toString() {
		return "BE in Station " + this.theMac.getMacAddress() + " AC: " + this.theAC;
	}

	public void discardQueue() {
		this.discardedCounter += theQueue.size();
		for (int i = this.theQueue.size() - 1; i >= 0; i--) {
			Vector<Object> params = new Vector<Object>();
			params.add(this.theQueue.get(i));
			params.add(this.theMac.getChannel());
			this.send(new JEEvent("push_back_packet", this.theMac.getHandlerId(), theUniqueEventScheduler.now(), params));
		}
		this.theQueue.clear();
		this.parameterlist.clear();
		this.parameterlist.add(this.theAC);
		this.send(new JEEvent("empty_queue_ind", this.theMac, theUniqueEventScheduler.now(), this.parameterlist));
	}

	/**
	 * @param discardedCounter
	 *            the discardedCounter to set
	 */
	public void setDiscardedCounter(int discardedCounter) {
		this.discardedCounter = discardedCounter;
	}

	/** @return the discardedCounter */
	public int getDiscardedCounter() {
		return discardedCounter;
	}

	// additional methods for provokable_nice_guy algorithm
	public JETime getTime() {
		return this.theUniqueEventScheduler.now();
	}

	public JEEventScheduler getTheUniqueEventScheduler() {
		return this.theUniqueEventScheduler;
	}

	public Integer getLongRetryCount() {
		return this.theLongRetryCnt;
	}

	public Integer getShortRetryCount() {
		return this.theShortRetryCnt;
	}

	public long getOverallTPCtr() {
		return overallTPCtr;
	}

	public long getSpecificTPCtr() {
		return specificTPCtr;
	}

	public int getFaultToleranceThreshold() {
		return faultToleranceThreshold;
	}

}
