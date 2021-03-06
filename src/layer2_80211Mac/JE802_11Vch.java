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

import java.util.Random;
import kernel.JEEvent;
import kernel.JEEventHandler;
import kernel.JEEventScheduler;
import kernel.JETime;
import layer1_80211Phy.JE802_11Phy;

public final class JE802_11Vch extends JEEventHandler {

	private JE802_11Mpdu theTxMpdu;

	private final JE802_11Phy thePhy;

	private final JE802_11Mac theMac;

	private enum vchState {
		idle, active, checkcollision
	}

	private vchState theVchState;

	private Integer theTransmittingEntityId;

	public JE802_11Vch(JEEventScheduler aScheduler, Random aGenerator, JE802_11Phy aPhy, JE802_11Mac aMac) {

		super(aScheduler, aGenerator);

		this.theVchState = vchState.idle;
		this.thePhy = aPhy;
		this.theMac = aMac;
		this.theTxMpdu = new JE802_11Mpdu();
		this.theTransmittingEntityId = null;
	}

	@Override
	public void event_handler(JEEvent anEvent) {

		JETime now = anEvent.getScheduledTime();
		String anEventName = anEvent.getName();

		// an event arrived
		this.message("VCH at Station " + this.theMac.getMacAddress() + " received event '" + anEventName + "'", 10);

		if (this.theVchState == vchState.idle) {

			if (anEventName.equals("stop_req")) { // ----------------------------------------------
				// ignore;

			} else if (anEventName.equals("start_req")) { // ----------------------------------------------
				this.theVchState = vchState.active;

			} else {
				this.error("undefined event '" + anEventName + "' in state " + this.theVchState.toString());
			}

		} else if (this.theVchState == vchState.active) {

			if (anEventName.equals("start_req")) { // ----------------------------------------------
				// ignore

			} else if (anEventName.equals("stop_req")) { // --------------------------------------------
				this.theVchState = vchState.idle;

			} else if (anEventName.equals("txattempt_req")) { // ----------------------------------------
				this.parameterlist = anEvent.getParameterList();
				this.theTxMpdu = (JE802_11Mpdu) this.parameterlist.elementAt(0);
				this.theTransmittingEntityId = anEvent.getSourceHandlerId();
				this.send(new JEEvent("checkdone_ind", this, theUniqueEventScheduler.now().plus(new JETime(Double.MIN_VALUE))));
				this.theVchState = vchState.checkcollision;

			} else if (anEventName.equals("tx_timeout_ind")) { // ----------------------------------------
				if (this.theTransmittingEntityId != null) {
					if (!anEvent.getSourceHandlerId().equals(this.theTransmittingEntityId)) {
					} else {
						this.theTransmittingEntityId = 0;
					}
				}
			} else {
				this.error("undefined event '" + anEventName + "' in state " + this.theVchState.toString());
			}

		} else if (this.theVchState == vchState.checkcollision) {

			if (anEventName.equals("txattempt_req")) { // ----------------------------------------------
				this.parameterlist = anEvent.getParameterList();
				JE802_11Mpdu anMpdu = (JE802_11Mpdu) this.parameterlist.elementAt(0);
				if (anMpdu.getAC() < this.theTxMpdu.getAC()) {
					this.theTxMpdu = anMpdu;
					this.theTransmittingEntityId = anEvent.getSourceHandlerId();
				}
			} else if (anEventName.equals("checkdone_ind")) { // --------------------------------------------
				this.parameterlist.clear();
				this.parameterlist.addElement(this.theTxMpdu);

				// forward MPDU to Phy:
				this.send(new JEEvent("PHY_TxStart_req", this.thePhy, now, this.parameterlist));
				this.theVchState = vchState.active;

				// inform other backoff entities. Send the winning frame up to
				// the Mac. The mac will then treat this frame as if it was
				// received from the outside.
				this.send(new JEEvent("VCH_TxStart_ind", this.theMac, now, this.parameterlist));

			} else {
				this.error("undefined event '" + anEventName + "' in state " + this.theVchState.toString());
			}
		}
	}

	public Integer getTransmitterId() {
		return this.theTransmittingEntityId;
	}

	public void setTransmitterId(Integer theTransmittingEntityId) {
		this.theTransmittingEntityId = theTransmittingEntityId;
	}
}