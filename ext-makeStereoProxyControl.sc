+ Object {
	makeStereoProxyControl { | channelOffset = 0, proxy |
		if(this.isKindOf(Function)) {
			^StereoSynthDefControl(this, channelOffset);
		} {
			^this.proxyControlClass.new(this, channelOffset);
		}
	}

	buildForStereoProxy { | proxy, channelOffset = 0, index |
		var channelConstraint, rateConstraint;
		var argNames = this.argNames;
		var defClass;
		if(this.isKindOf(Function)) {
			defClass = if(proxy.isKindOf(StereoNodeProxy)) {
				StereoProxySynthDef
			} {
				ProxySynthDef
			};
			if(proxy.fixedBus) {
				channelConstraint = proxy.numChannels;
				rateConstraint = proxy.rate;
			};
			^defClass.new(
				SystemSynthDefs.tempNamePrefix ++ proxy.generateUniqueName ++ index,
				this.prepareForProxySynthDef(proxy, channelOffset),
				proxy.nodeMap.ratesFor(argNames),
				nil,
				true,
				channelOffset,
				channelConstraint,
				rateConstraint
			);
		} {
			^this.buildForProxy(proxy, channelOffset, index)
		};
	}
}

// probably need to check channels and wrap?
+ AbstractPlayControl {
	makeStereoProxyControl { ^this.deepCopy } //already wrapped, but needs to be copied
}

+ ProxyNodeMap {
	// work around a bug where asControlInput shouldn't be called
		controlNames {
		var res = Array.new;
		this.keysValuesDo { |key, value|
			var rate;
			// value = value.asControlInput;
			rate = if(value.rate == \audio) { \audio } { \control };
			res = res.add(ControlName(key, nil, rate, value))
		};
		^res
	}
}
