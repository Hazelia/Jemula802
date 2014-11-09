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

package layer1_802Phy;

import emulator.JE802StatEval;
import gui.JE802Gui;

import java.util.List;
import java.util.Random;

import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;

import kernel.JEEvent;
import kernel.JEEventHandler;
import kernel.JEEventScheduler;
import kernel.JETime;
import layer0_medium.JEWirelessMedium;
import layer0_medium.JEWirelessChannel;
import layer2_80211Mac.JE802_11Mac;
import layer2_802Mac.JE802_Mac;

import org.w3c.dom.Element;
import org.w3c.dom.Node;

public abstract class JE802Phy extends JEEventHandler {

	protected JE802_Mac theMac;

	protected List<JE802PhyMode> thePhyModeList;

	protected JE802PhyMode theDefaultPhyMode;

	protected JE802PhyMode theCurrentPhyMode;

	protected final JE802Gui theUniqueGui;

	protected final JEWirelessMedium theUniqueRadioChannel;

	protected JE802Mobility mobility;

	protected boolean useBasicModes;

	protected JETime currentTxEnd = new JETime(-1.0);

	protected JETime currentRxEnd = new JETime(-1.0);

	protected enum state {
		idle, active, active_sync
	}

	protected state theState;

	protected int concurrentRx = 0;

	protected int maxConcurrentRx = 0;

	protected final JE802StatEval statEval;

	protected Boolean useGnuRadio;

	protected JEAntenna theAntenna;

	protected int currentChannelNumber;

	protected double currentTransmitPowerLevel_dBm;

	protected double currentTransmitPower_mW;

	protected boolean onceTx = false;

	public JE802Phy(JEEventScheduler aScheduler, JE802StatEval statEval, Random aGenerator, JEWirelessMedium aChannel,
			JE802Gui aGui, Node aTopLevelNode) throws XPathExpressionException {

		super(aScheduler, aGenerator);
		this.theUniqueGui = aGui;
		this.theState = state.idle;
		this.theUniqueRadioChannel = aChannel;
		this.statEval = statEval;

		Element phyElem = (Element) aTopLevelNode;

		if (phyElem.getTagName().startsWith("JE802") && phyElem.getTagName().endsWith("PHY")) {

			XPath xpath = XPathFactory.newInstance().newXPath();

			String basicModes = phyElem.getAttribute("useBasicModesForCtrl");
			if (basicModes.equals("")) {
				this.useBasicModes = false;
			} else {
				this.useBasicModes = new Boolean(basicModes);
			}

			this.theCurrentPhyMode = this.theDefaultPhyMode;

			// setup antenna (like specified in XML if "JEAntenna" element
			// exists, uniform/omnidirectional otherwise
			Element antennaElem = (Element) xpath.evaluate("JEAntenna", phyElem, XPathConstants.NODE);
			if (antennaElem != null) {
				double gain = new Double(antennaElem.getAttribute("gain_dBi"));
				if (gain < 0) {
					this.warning("Antenna gain must be positive, using an omnidirectional antenna instead.");
				}

				/*
				 * double gainLow = new
				 * Double(antennaElem.getAttribute("gainLow_dBi")); if(gainLow
				 * >= 0) { this.warning("Station " + this.theMac.getMacAddress()
				 * +
				 * ": Antenna gainLow must be negative, using an omnidirectional antenna instead."
				 * ); }
				 */

				this.theAntenna = new JEAntenna(new Double(antennaElem.getAttribute("xDirection")), new Double(
						antennaElem.getAttribute("yDirection")), new Double(antennaElem.getAttribute("zDirection")), gain,
						new Double(antennaElem.getAttribute("angle_degrees")));
			} else {
				this.warning("Station " + this.theMac.getMacAddress()
						+ ": No JEAntenna XML tag found. Assuming an omnidirectional antenna.");
				this.useOmnidirectionalAntenna();
			}
		} else {
			this.error("Construction of JE802Phy did not receive JE802Phy xml node");
		}
	}

	@Override
	public abstract void event_handler(final JEEvent anEvent);

	/*
	 * (non-Javadoc)
	 * 
	 * @see jemula.kernel.JEEventHandler#event_handler(jemula.kernel.JEEvent)
	 */

	public double getCoverageRange_m() {
		return this.theUniqueRadioChannel.getCoverageRange_m();
	}

	public JE802Mobility getMobility() {
		return mobility;
	}

	public JE802PhyMode getCurrentPhyMode() {
		return theCurrentPhyMode;
	}

	public JE802PhyMode getBasicPhyMode(JE802PhyMode aPhyMode) {
		if (useBasicModes) {
			if (aPhyMode.getModeId() >= 5) {
				return thePhyModeList.get(4); // 24Mbps
			} else if (aPhyMode.getModeId() >= 3) {
				return this.thePhyModeList.get(2); // 12Mbps
			} else {
				return this.thePhyModeList.get(0); // 6Mbps
			}
		}
		return aPhyMode;
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

	/*
	 * @Override public String toString() { return ("Phy" +
	 * this.theMac.getMACAddress() + "_Rx" + this.dot11CurrentChannelNumberRX +
	 * "_Tx" + this.dot11CurrentChannelNumberTX); }
	 */

	public void setThePhyModeList(List<JE802PhyMode> thePhyModeList) {
		this.thePhyModeList = thePhyModeList;
		if (this.theDefaultPhyMode == null) {
			this.theDefaultPhyMode = this.thePhyModeList.get(0);
			this.theCurrentPhyMode = this.theDefaultPhyMode;
		}
	}

	public void setMobility(JE802Mobility mobility) {
		this.mobility = mobility;
	}

	public void setOnceTx(boolean onceTx) {
		this.onceTx = onceTx;
	}

	public boolean isOnceTx() {
		return onceTx;
	}

	@Override
	public int hashCode() {
		return this.getHandlerId();
	}

	public boolean isCcaBusy() {
		double powerLevel_mW = this.theUniqueRadioChannel.getRxPowerLevel_mW(this);
		boolean busy = powerLevel_mW > this.theUniqueRadioChannel.getBusyPowerLevel_mW();
		return busy;
	}

	@Override
	public String toString() {
		return this.currentChannelNumber + "/" + this.currentRxEnd + "/" + this.currentTxEnd + super.toString();
	}

	public JEAntenna getAntenna() {
		return theAntenna;
	}

	public void useOmnidirectionalAntenna() {
		this.theAntenna = new JEAntenna(1, 0, 0, 0, 180);
	}

	public double getCurrentTransmitPowerLevel_dBm() {
		return currentTransmitPowerLevel_dBm;
	}

	public void setCurrentTransmitPowerLevel_dBm(double currentTransmitPowerLevel_dBm) {
		this.currentTransmitPowerLevel_dBm = currentTransmitPowerLevel_dBm;
		this.currentTransmitPower_mW = Math.pow(10,(currentTransmitPowerLevel_dBm) / 10);
 
	}

	public double getCurrentTransmitPower_mW() {
		return currentTransmitPower_mW;
	}

	public void setCurrentTransmitPower_mW(double currentTransmitPower_mW) {
		this.currentTransmitPower_mW = currentTransmitPower_mW;
		this.currentTransmitPowerLevel_dBm = 10 * Math.log10(this.currentTransmitPower_mW);
	}

	public void setMac(JE802_11Mac mac) {
		this.theMac = mac;
	}

	public JE802_Mac getMac() {
		return theMac;
	}

	public int getCurrentChannel() {
		return this.currentChannelNumber;
	}

	public void setChannel(int channel) {
		this.currentChannelNumber = channel;
	}

}
