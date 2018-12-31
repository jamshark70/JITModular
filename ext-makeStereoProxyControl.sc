+ Object {
	makeStereoProxyControl { | channelOffset = 0, proxy |
		if(/*proxy.rate.debug("rate fml") == \audio and: {*/ this.isKindOf(Function).debug("isFunction") /*}*/) {
			^StereoSynthDefControl(this, channelOffset);
		} {
			^this.proxyControlClass.debug("falling back to proxyControlClass").new(this, channelOffset);
		}
	}

	buildForStereoProxy { | proxy, channelOffset = 0, index |
		var channelConstraint, rateConstraint;
		var argNames = this.argNames;
		var defClass;
		if(this.isKindOf(Function)) {
			defClass = if(proxy.debug("proxy").isKindOf(StereoNodeProxy).debug("it's stereo") /*and: { proxy.rate.debug("rate") == \audio }*/) {
				StereoProxySynthDef
			} {
				ProxySynthDef
			};
			if(proxy.fixedBus) {
				channelConstraint = proxy.numChannels;
				rateConstraint = proxy.rate;
			};
			thisProcess.interpreter.a = defClass.debug("making one of these").new(
				SystemSynthDefs.tempNamePrefix ++ proxy.generateUniqueName ++ index,
				this.prepareForProxySynthDef(proxy, channelOffset),
				proxy.nodeMap.ratesFor(argNames),
				nil,
				true,
				channelOffset,
				channelConstraint,
				rateConstraint
			);
			^thisProcess.interpreter.a
		} {
			^this.buildForProxy(proxy, channelOffset, index)
		};
	}
}

// probably need to check channels and wrap?
+ AbstractPlayControl {
	makeStereoProxyControl { ^this.deepCopy } //already wrapped, but needs to be copied
}

