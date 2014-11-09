/*
 * 
 * This is Jemula.
 *
 *    Copyright (c) Stefan Mangold
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
package layer2_802Mac;

import emulator.JE802StatEval;
import gui.JE802Gui;

import java.util.Random;

import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;

import org.w3c.dom.Node;

import kernel.JEEvent;
import kernel.JEEventHandler;
import kernel.JEEventScheduler;
import kernel.JETime;
import layer1_802Phy.JE802Phy;

public abstract class JE802_Mac extends JEEventHandler {

	protected int theMacAddress;

	protected int smeHandlerId;

	protected final JE802Gui theUniqueGui;

	protected final XPath xpath = XPathFactory.newInstance().newXPath();

	protected JETime currentTransmissionEnd = new JETime(-1.0);

	protected final JE802StatEval statEval;

	/**
	 * @param aTopLevelNode
	 * @throws XPathExpressionException
	 */
	public JE802_Mac(final JEEventScheduler aScheduler, final JE802StatEval statEval, final Random aGenerator,
			final JE802Gui aGui, final Node aTopLevelNode, final int smeHandlerId) throws XPathExpressionException {

		super(aScheduler, aGenerator);
		this.theUniqueGui = aGui;
		this.statEval = statEval;
		this.smeHandlerId = smeHandlerId;
		this.theState = state.active;
	}

	public int getMacAddress() {
		return this.theMacAddress;
	}

	public void setMACAddress(int aMacAddress) {
		this.theMacAddress = aMacAddress;
	}

	@Override
	public abstract void event_handler(final JEEvent anEvent);

	
	public JEEventScheduler getTheUniqueEventScheduler() {
		return this.theUniqueEventScheduler;
	}
	
	

}
