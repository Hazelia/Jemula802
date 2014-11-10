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

package layer1_80211Phy;

import emulator.JE802StatEval;
import gui.JE802Gui;
import java.util.List;
import java.util.Random;
import java.util.Vector;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;
import kernel.JEEvent;
import kernel.JEEventScheduler;
import kernel.JETime;
import layer0_medium.JEWirelessMedium;
import layer0_medium.JEWirelessChannel;
import layer1_802Phy.JE802Phy;
import layer1_802Phy.JE802Ppdu;
import layer2_80211Mac.JE802_11Mac;
import layer2_80211Mac.JE802_11Mpdu;

import org.w3c.dom.Element;
import org.w3c.dom.Node;

public class JE802_11Phy extends JE802Phy {

	private JETime aSlotTime;

	private JETime SIFS;

	private JETime SymbolDuration;

	private int PLCPTail_bit;

	private int PLCPServiceField_bit;

	private JETime PLCPHeaderWithoutServiceField;

	private JETime PLCPPreamble;

	private JETime PLCPHeaderDuration;

	public JE802_11Phy(JEEventScheduler aScheduler, JE802StatEval statEval,
			Random aGenerator, JEWirelessMedium aChannel, JE802Gui aGui,
			Node aTopLevelNode) throws XPathExpressionException {

		super(aScheduler, statEval, aGenerator, aChannel, aGui, aTopLevelNode);

		Element phyElem = (Element) aTopLevelNode;

		if (phyElem.getTagName().equals("JE80211PHY")) {

			XPath xpath = XPathFactory.newInstance().newXPath();

			// mib
			Element mibElem = (Element) xpath.evaluate("MIB802.11abgn",
					phyElem, XPathConstants.NODE);
			if (mibElem != null) {
				this.aSlotTime = new JETime(new Double(
						mibElem.getAttribute("aSlotTime")));
				this.SIFS = new JETime(new Double(mibElem.getAttribute("SIFS")));
				this.currentTransmitPowerLevel_dBm = new Double(
						mibElem.getAttribute("dot11CurrentTransmitPowerLevel_dBm"));
				this.currentTransmitPower_mW = Math.pow(10,
						(currentTransmitPowerLevel_dBm) / 10);
				this.currentChannelNumber = new Integer(
						mibElem.getAttribute("dot11CurrentChannelNumber"));
			} else {
				this.error("No MIB802.11abgn definition found !!!");
			}

			this.SymbolDuration = new JETime(new Double(
					phyElem.getAttribute("SymbolDuration_ms")));
			this.PLCPTail_bit = new Integer(
					phyElem.getAttribute("PLCPTail_bit"));
			this.PLCPServiceField_bit = new Integer(
					phyElem.getAttribute("PLCPServiceField_bit"));
			this.PLCPHeaderWithoutServiceField = new JETime(new Double(
					phyElem.getAttribute("PLCPHeaderWithoutServiceField_ms")));
			this.PLCPPreamble = new JETime(new Double(
					phyElem.getAttribute("PLCPPreamble_ms")));
			this.PLCPHeaderDuration = this.PLCPPreamble
					.plus(PLCPHeaderWithoutServiceField);

		} else {
			this.error("Construction of JE802Phy did not receive JE802Phy xml node");
		}

	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see jemula.kernel.JEEventHandler#event_handler(jemula.kernel.JEEvent)
	 */
	@Override
	public void event_handler(JEEvent anEvent) {

		JETime now = anEvent.getScheduledTime();
		String anEventName = anEvent.getName();

		if (this.theState == state.idle) {

			if (anEventName.equals("stop_req")) {
				// ignore;

			} else if (anEventName.equals("start_req")) {
				this.message(
						"PHY received event start_req while in idle state", 1);

				this.parameterlist.clear();
				this.parameterlist.add(this);
				this.send(new JEEvent("register_req",
						this.theUniqueRadioChannel.getHandlerId(), now,
						this.parameterlist));

				if (mobility.isMobile()) {
					this.send(new JEEvent("location_update", this, mobility
							.getTraceStartTime()));
				}

				this.theState = state.active;

			} else {
				this.error("undefined event '" + anEventName + "' in state "
						+ this.theState);
			}

		} else if (this.theState == state.active
				|| this.theState == state.active_sync) {

			if (anEventName.equals("PHY_SyncEnd_ind")) { // Pietro: we need to
				// check which MAC
				// is present in the
				// packet
				this.parameterlist.clear();
				this.parameterlist = anEvent.getParameterList();
				JE802_11Ppdu aPpdu = (JE802_11Ppdu) this.parameterlist
						.elementAt(0);
				if (!aPpdu.isJammed() && currentTxEnd.isEarlierThan(now)
						&& concurrentRx == 1) {
					JE802_11Mpdu aMpdu = aPpdu.getMpdu();
					this.parameterlist = new Vector<Object>();
					this.parameterlist.add(aMpdu.getDA());
					this.parameterlist.add(aMpdu.getAC());
					this.parameterlist.add(aMpdu.getNav());
					// this.parameterlist.addAll(aMpdu.getStartParameterList());
					this.send(new JEEvent("PHY_RxStart_ind", this.theMac, now,
							this.parameterlist));
				}
				this.theState = state.active;

			} else if (anEventName.equals("MEDIUM_RxStart_ind")) {
				this.parameterlist.clear();
				this.parameterlist = anEvent.getParameterList();
				concurrentRx++;
				if (concurrentRx > maxConcurrentRx) {
					maxConcurrentRx = concurrentRx;
				}
				JE802Ppdu aPpdu = (JE802Ppdu) this.parameterlist.elementAt(0);
				if (!aPpdu.isJammed() && this.currentTxEnd.isEarlierThan(now)
						&& this.currentRxEnd.isEarlierThan(now)) {
					this.send(new JEEvent("PHY_SyncStart_ind", this.theMac, now));
					this.send(new JEEvent("PHY_SyncEnd_ind", this, now
							.plus(PLCPHeaderDuration), this.parameterlist));
					// this.theState = state.active_sync; // for a short amount
					// of time, change state to sync, until SyncEnt
				}
				JETime receptionDuration = aPpdu.getMpdu().getTxTime();
				statEval.recordPowerRx(theMac.getMacAddress(),
						this.getHandlerId(), now, receptionDuration);
				JETime newRxEnd = now.plus(aPpdu.getMpdu().getTxTime());
				if (newRxEnd.isLaterThan(currentRxEnd)) {
					this.currentRxEnd = newRxEnd;
				}

			} else if (anEventName.equals("MEDIUM_RxEnd_ind")) {
				concurrentRx--;
				if (now.isLaterThan(currentRxEnd)) {
					maxConcurrentRx = 0;
				}
				Vector<Object> parameterList = new Vector<Object>();
				JE802Ppdu aPpdu = (JE802Ppdu) anEvent.getParameterList().get(0);
				// if the current rxStart was later than the txEnd otherwise,
				// don't even report because its garbage anyway and we are not
				// in reception state
				if (now.minus(aPpdu.getMpdu().getTxTime()).isLaterThan(
						currentTxEnd)) {
					if (aPpdu.isJammed() || maxConcurrentRx > 1) {
						parameterList.add(null);
					} else {
						parameterList.add(aPpdu.getMpdu());
					}
					this.send(new JEEvent("PHY_RxEnd_ind", this.theMac
							.getHandlerId(), theUniqueEventScheduler.now(),
							parameterList));
				}
				if (now.getTimeMs() == currentRxEnd.getTimeMs()) {
					maxConcurrentRx = 0;
				}

			} else if (anEventName.equals("PHY_ChannelSwitch_req")) {

				int from = this.currentChannelNumber;
				this.currentChannelNumber = (Integer) anEvent
						.getParameterList().elementAt(0);
				this.concurrentRx = 0;
				this.currentRxEnd = new JETime(-1.0);
				this.currentTxEnd = new JETime(-1.0);
				this.parameterlist.clear();
				this.parameterlist.add(this);
				this.parameterlist.add(from);
				this.parameterlist.add(currentChannelNumber);
				// do not switch channel while transmitting a packet
				this.send(new JEEvent("channel_switch_req",
						this.theUniqueRadioChannel.getHandlerId(), now,
						this.parameterlist));

			} else if (anEventName.equals("PHY_TxStart_req")) {

				this.parameterlist.clear();
				this.parameterlist = anEvent.getParameterList();

				// Pietro: here we need to know if either a 802.11 or 802.15
				// frame shall be transmitted
				JE802_11Mpdu aMpdu = (JE802_11Mpdu) this.parameterlist
						.elementAt(0);
				statEval.addPacketForCounts(aMpdu);
				JE802_11Ppdu aPpdu = new JE802_11Ppdu(aMpdu,
						this.currentTransmitPowerLevel_dBm,
						this.currentChannelNumber);
				JETime txDuration = aMpdu.getTxTime();
				statEval.recordPowerTx(theMac.getMacAddress(),
						this.getHandlerId(), now, txDuration);
				currentTxEnd = now.plus(aMpdu.getTxTime());
				this.parameterlist.clear();
				this.parameterlist.addElement(this);
				this.parameterlist.addElement(aPpdu);

				// show frame in GUI
				if (theUniqueGui != null) {
					String type = aMpdu.getType() + " " + aMpdu.getSeqNo();
					if (aMpdu.isData()) {
						String ipPacketType = aMpdu.getPayload().getClass()
								.getName();
						if (ipPacketType.contains("RREQ")) {
							type = type + " RREQ";
						} else if (ipPacketType.contains("RREP")) {
							type = type + " RREP" + aMpdu.getPayload().getSA();
						} else if (ipPacketType.contains("RRER")) {
							type = type + " RERR";
						}
					}
					String label = "DA:" + aMpdu.getDA();
					if (aMpdu.getPayload() != null) {
						if (aMpdu.getPayload().getPayload().isAck()) {
							label = label + " /TCPACK/";
						} else if (aMpdu.getPayload().getPayload().isTCP()) {
							label = label
									+ " /TCPDATA "
									+ aMpdu.getPayload().getPayload()
											.getSeqNo() + "/";
						}
					}
					theUniqueGui.addFrame(theUniqueEventScheduler.now(),
							aMpdu.getTxTime(), aPpdu.getChannelNumber(),
							aMpdu.getSA(), type,
							(aMpdu.getPhyMode().toString()), label,
							this.currentChannelNumber);
				}

				// forward MPDU as PPDU to channel:
				this.send(new JEEvent("MEDIUM_TxStart_req",
						this.theUniqueRadioChannel.getHandlerId(), now,
						this.parameterlist));

				this.parameterlist.clear();
				this.parameterlist.addElement(aPpdu.getMpdu().getAC());
				this.send(new JEEvent("PHY_TxEnd_ind", this.theMac, now
						.plus(aMpdu.getTxTime()), this.parameterlist));

			} else if (anEventName.equals("location_update")) {

			} else if (anEventName.equals("start_req")) {
				// ignore

			} else if (anEventName.equals("stop_req")) {
				this.theState = state.idle;

			} else {
				this.error("undefined event '" + anEventName + "' in state "
						+ this.theState);
			}

		} else {
			this.error("undefined event handler state.");
		}
	}

	public int getPLCPServiceField_bit() {
		return PLCPServiceField_bit;
	}

	public JETime getSlotTime() {
		return aSlotTime;
	}

	public JETime getSIFS() {
		return SIFS;
	}

	public JETime getPLCPHeaderWithoutServiceField() {
		return PLCPHeaderWithoutServiceField;
	}

	public JETime getSymbolDuration() {
		return SymbolDuration;
	}

	public JETime getPLCPPreamble() {
		return PLCPPreamble;
	}

	public JE802_11Mac getMac() {
		return (JE802_11Mac) theMac;
	}

	public double getCoverageRange_m() {
		return this.theUniqueRadioChannel.getCoverageRange_m();
	}

	public Integer getPLCPTail_bit() {
		return PLCPTail_bit;
	}

	public void setCurrentPhyMode(String aName) {
		if (aName.equals("default")) {
			this.theCurrentPhyMode = this.theDefaultPhyMode;
		} else if (aName.equals(this.theCurrentPhyMode.getName())) {
			// do nothing
		} else {
			for (int cnt = 0; cnt < this.thePhyModeList.size(); cnt++) {
				if (aName.equals(this.thePhyModeList.get(cnt).getName())) {
					this.theCurrentPhyMode = this.thePhyModeList.get(cnt);
					break;
				}
			}
		}
	}

	public List<JEWirelessChannel> getAvailableChannels() {
		return theUniqueRadioChannel.getAvailableChannels();
	}

	public void setMac(JE802_11Mac theMac) {
		this.theMac = theMac;
	}

	@Override
	public String toString() {
		return ("Phy" + this.theMac.getMacAddress() + "_" + this.currentChannelNumber);
	}

	public JETime getPLCPHeaderDuration() {
		return this.PLCPHeaderDuration;
	}

	@Override
	public int hashCode() {
		return this.getHandlerId();
	}

	public boolean isCcaBusy() {
		double powerLevel_mW = this.theUniqueRadioChannel
				.getRxPowerLevel_mW(this);
		boolean busy = powerLevel_mW > this.theUniqueRadioChannel
				.getBusyPowerLevel_mW();
		return busy;
	}

}
