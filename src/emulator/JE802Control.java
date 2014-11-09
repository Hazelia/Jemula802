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

package emulator;

import gui.JE802Gui;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import javax.swing.JFrame;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;

import kernel.JEEvent;
import kernel.JEEventScheduler;
import kernel.JETime;
import kernel.JEmula;
import layer0_medium.JE8MediumWithInterference;
import layer0_medium.JEWirelessMedium;
import layer0_medium.JEMediumUnitDisk;

import layer1_802Phy.JE802PhyMode;
import layer3_network.JE802RoutingConstants;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import animation.JE802KmlWriter;

import station.JE802Station;

/**
 * @author Stefan Mangold
 * 
 */
public class JE802Control extends JEmula {

	private List<JE802Station> stations;

	private boolean showGui;

	private boolean useInterferenceModel;

	private String path2Results;

	private JEEventScheduler theUniqueEventScheduler;

	private JEWirelessMedium theUniqueWirelessMedium;

	private Random theUniqueRandomBaseGenerator;

	private JE802Gui theUniqueGui;

	private JE802StatEval statEval;

	private JE802KmlWriter kmlWriter;

	private Document configuration;

	private List<JE802PhyMode> phyModes;

	public JE802Control(Document aDocument, boolean showGui) {
		this.configuration = aDocument;
		this.theUniqueEventScheduler = new JEEventScheduler();
		this.showGui = showGui;
		this.kmlWriter = null;
		this.phyModes = new ArrayList<JE802PhyMode>();
		if (this.showGui) {
			theUniqueGui = new JE802Gui(aDocument.getBaseURI());
			theUniqueGui.setVisible(true);
			theUniqueGui.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
		} else {
			theUniqueGui = null;
		}
		this.stations = new ArrayList<JE802Station>(); // queue of theStations
		this.parse_xml_and_create_entities();
		this.path2Results = statEval.getPath2Results();
		this.theUniqueEventScheduler.setPath2Results(this.path2Results);
	}

	public void emulate() {
		this.theUniqueEventScheduler.start();
		// the event scheduler will now start, and then control the entire
		// emulation
	}

	public void animate() {
		if (kmlWriter != null) {
			this.message(
					"Creating visualization ...",
					100);
//			long beforeAnim = System.currentTimeMillis();
			kmlWriter.createDOM();
			kmlWriter.writeDOMtoFile();
			kmlWriter.createKMZArchive();
			this.message("done.");
//			this.message(
//					"Visualization done (in "
//							+ (System.currentTimeMillis() - beforeAnim) / 600
//							+ " seconds).", 100);
		}
		this.configuration = null;
	}

	public JETime getSimulationTime() {
		return this.theUniqueEventScheduler.now();
	}

	public String getPath2Results() {
		return this.path2Results;
	}

	public void setSimulationEnd(JETime time) {
		this.theUniqueEventScheduler.setEmulationEnd(time);
	}

	public void setRandomSeed(long newSeed) {
		this.theUniqueRandomBaseGenerator.setSeed(newSeed);
	}

	private void parse_xml_and_create_entities() {
		Node theTopLevelNode = configuration.getFirstChild();
		XPath xpath = XPathFactory.newInstance().newXPath();
		try {

			// parseControl element
			createControlElement(theTopLevelNode, xpath);
			// wireless channel definitions
			createWirelessChannels(theTopLevelNode, xpath);
			createAnimation(theTopLevelNode, xpath);
			createPhyModes(theTopLevelNode, xpath);
			createRoutingConstants(theTopLevelNode, xpath);
			createStations(theTopLevelNode, xpath);
		} catch (XPathExpressionException e) {
			e.printStackTrace();
		}
	}


	private void createRoutingConstants(Node theTopLevelNode, XPath xpath)
			throws XPathExpressionException {
		Element routingParameters = (Element) xpath.evaluate(
				"JE802RoutingParameters", theTopLevelNode, XPathConstants.NODE);
		if (routingParameters == null) {
			this.warning("No <JE802RoutingParameters> in XML, using default values");
			return;
		}
		String enabledStr = routingParameters.getAttribute("routingEnabled");
		if (!enabledStr.isEmpty()) {
			JE802RoutingConstants.routingEnabled = new Boolean(enabledStr);
		} else {
			JE802RoutingConstants.routingEnabled = false;
		}

		// dynamicChannelSwitching enabled
		String switchingStr = routingParameters
				.getAttribute("channelSwitchingEnabled");
		if (!switchingStr.isEmpty()) {
			JE802RoutingConstants.channelSwitchingEnabled = new Boolean(
					switchingStr);
		} else {
			JE802RoutingConstants.channelSwitchingEnabled = false;
		}

		String mcrMetricStr = routingParameters
				.getAttribute("multiChannelPathMetricEnabled");
		if (!mcrMetricStr.isEmpty()) {
			JE802RoutingConstants.MCRMetricEnabled = new Boolean(mcrMetricStr);
		} else {
			JE802RoutingConstants.MCRMetricEnabled = false;
		}

		String routeTimeout = routingParameters
				.getAttribute("activeRouteTimeout_ms");
		if (!routeTimeout.isEmpty()) {
			double timeOut = new Double(routeTimeout);
			JE802RoutingConstants.ACTIVE_ROUTE_TIMEOUT = new JETime(timeOut);
		} else {
			JE802RoutingConstants.ACTIVE_ROUTE_TIMEOUT = new JETime(3000.0);
		}

		String ipHeaderLengthStr = routingParameters
				.getAttribute("ipHeaderByte");
		if (!ipHeaderLengthStr.isEmpty()) {
			int headerLength = new Integer(ipHeaderLengthStr);
			JE802RoutingConstants.IP_HEADER_BYTE = headerLength;
		} else {
			JE802RoutingConstants.IP_HEADER_BYTE = 20;
		}

		String brokenStr = routingParameters
				.getAttribute("brokenLinkAfterLoss");
		if (!brokenStr.isEmpty()) {
			int brokenAfter = new Integer(brokenStr);
			JE802RoutingConstants.LINK_BREAK_AFTER_LOSS = brokenAfter;
		} else {
			JE802RoutingConstants.LINK_BREAK_AFTER_LOSS = 3;
		}

		String ttlStr = routingParameters.getAttribute("maxTTL");
		if (!ttlStr.isEmpty()) {
			int maxTTL = new Integer(ttlStr);
			JE802RoutingConstants.maxTTL = maxTTL;
		} else {
			JE802RoutingConstants.maxTTL = 5;
		}

		String helloIntervalStr = routingParameters
				.getAttribute("helloInterval_ms");
		if (!helloIntervalStr.isEmpty()) {
			double interval = new Integer(helloIntervalStr);
			JE802RoutingConstants.HELLO_INTERVAL_MS = new JETime(interval);
		} else {
			JE802RoutingConstants.HELLO_INTERVAL_MS = new JETime(2000.0);
		}

		String channelDelayStr = routingParameters
				.getAttribute("channelSwitchingDelay_ms");
		if (!channelDelayStr.isEmpty()) {
			double delay = new Double(channelDelayStr);
			JE802RoutingConstants.CHANNEL_SWITCHING_DELAY = new JETime(delay);
		} else {
			JE802RoutingConstants.CHANNEL_SWITCHING_DELAY = new JETime(1.0);
		}
	}

	private void createStations(Node theTopLevelNode, XPath xpath)
			throws XPathExpressionException {
		// theStations
		NodeList stationNodeList = (NodeList) xpath.evaluate("JE802Station",
				theTopLevelNode, XPathConstants.NODESET);
		Element animationNode = (Element) xpath.evaluate("//JE802Animation",
				theTopLevelNode, XPathConstants.NODE);
		Double longitude = new Double(
				animationNode.getAttribute("baseLongitude"));
		Double latitude = new Double(animationNode.getAttribute("baseLatitude"));
		for (int i = 0; i < stationNodeList.getLength(); i++) {
			Node stationNode = stationNodeList.item(i);
			JE802Station station = new JE802Station(theUniqueEventScheduler,
					theUniqueWirelessMedium, theUniqueRandomBaseGenerator,
					theUniqueGui, statEval, stationNode, phyModes, longitude,
					latitude);
			this.stations.add(station);
			if (!useInterferenceModel
					&& station.getPhy().getAntenna().isDirectional()) {
				this.warning("Station "
						+ station.getMac().getMacAddress()
						+ ": Directional antennas are only allowed when using the interference model."
						+ " An omnidirectional antenna is used instead.");
				station.getPhy().useOmnidirectionalAntenna();
			}
		}
	}

	private void createPhyModes(Node theTopLevelNode, XPath xpath)
			throws XPathExpressionException {
		// phymodes
		Node phyModesNode = (Node) xpath.evaluate("JE802PhyModes",
				theTopLevelNode, XPathConstants.NODE);
		if (phyModesNode != null) {
			NodeList phyModeList = (NodeList) xpath.evaluate("aPhyMode",
					phyModesNode, XPathConstants.NODESET);
			for (int i = 0; i < phyModeList.getLength(); i++) {
				Node phyNode = phyModeList.item(i);
				phyModes.add(new JE802PhyMode(phyNode));
			}
			statEval.setPhyModes(phyModes);
		} else {
			this.error("No JE802PhyModes node specified in xml");
		}
	}

	private void createAnimation(Node theTopLevelNode, XPath xpath)
			throws XPathExpressionException {
		// animation
		String resultPath = statEval.getPath2Results();
		Element animationNode = (Element) xpath.evaluate("//JE802Animation",
				theTopLevelNode, XPathConstants.NODE);
		if (new Boolean(animationNode.getAttribute("generateGoogleEarth"))) {
			kmlWriter = new JE802KmlWriter(animationNode, resultPath,
					this.configuration.getDocumentURI(), this.stations,
					this.theUniqueWirelessMedium.getCoverageRange_m());
		}

	}

	private void createControlElement(Node theTopLevelNode, XPath xpath)
			throws XPathExpressionException {
		// find control element
		Element controlElem = (Element) xpath.evaluate("JE802Control",
				theTopLevelNode, XPathConstants.NODE);
		if (controlElem != null) {
			// set emulation duration
			JETime anEmulationEnd = new JETime(Double.parseDouble((controlElem)
					.getAttribute("EmulationDuration_ms")));
			theUniqueEventScheduler.setEmulationEnd(anEmulationEnd);

			// stat eval
			Node statEvalNode = (Node) xpath.evaluate("JE802StatEval",
					controlElem, XPathConstants.NODE);
			if (statEvalNode != null) {
				long aSeed = new Long(
						((Element) statEvalNode).getAttribute("seed"));
				theUniqueRandomBaseGenerator = new Random(aSeed);
				statEval = new JE802StatEval(theUniqueEventScheduler,
						theUniqueRandomBaseGenerator, statEvalNode);
				statEval.send(new JEEvent("start_req", statEval,
						theUniqueEventScheduler.now()));
			} else {
				this.warning("No JE802StatEval node specified in xml");
			}
		} else {
			this.warning("No JE802Control node specified in xml");
		}
	}

	private void createWirelessChannels(Node theTopLevelNode, XPath xpath)
			throws XPathExpressionException {
		Node channelNode = null;
		boolean channel_is_defined = false;

		channelNode = (Node) xpath.evaluate("JEWirelessChannels",
				theTopLevelNode, XPathConstants.NODE);
		if (channelNode != null) {
			channel_is_defined = true;
			Node interference = (Node) xpath.evaluate(
					"JEWirelessChannels/@useInterference", theTopLevelNode,
					XPathConstants.NODE);
			if (interference != null) {
				useInterferenceModel = new Boolean(interference.getNodeValue());
				if (useInterferenceModel) {
					theUniqueWirelessMedium = new JE8MediumWithInterference(
							theUniqueEventScheduler,
							theUniqueRandomBaseGenerator, channelNode);
				} else {
					theUniqueWirelessMedium = new JEMediumUnitDisk(
							theUniqueGui, theUniqueEventScheduler,
							theUniqueRandomBaseGenerator, channelNode);
				}
			} else {
				theUniqueWirelessMedium = new JEMediumUnitDisk(theUniqueGui,
						theUniqueEventScheduler, theUniqueRandomBaseGenerator,
						channelNode);
			}

		} else {
			this.error("No JEWirelessChannels node specified in xml");
		}
		if (channel_is_defined == false) {
			this.error("No JEWirelessChannels specified in xml");
		}
	}
}