/***

Notice of GPL compliance

Much of the code in this file consists of modified versions of code from
the Just-In-Time Library, released under GPLv3 as part of the SuperCollider
package. I needed to override just a couple of critical places, but there
aren't many method hooks to do so.

Full credit to the authors of JITLib: Julian Rohrhuber, Alberto de Campo,
and many others.

I intend these methods as derivative works, released in source form back
to the community, under GPLv3. I am expressly not claiming authorship
for the portions of this code not written by myself.

-- H. James Harkins

***/


// hjh: I need to be able to import the fix for #4316
// into any JITModular environment easily.
// So I'm copying the lot, changing key methods to "*2" variants.
// Then users can do "p.document2" and get a working code string.
// It's a hack, but I'm under some logistical constraints.

+ AbstractPlayControl {

	storeOn2 { | stream |
		source.storeOn2(stream)
	}
}

+ Symbol {

	isBasicOperator {
		^#['+', '-', '*', '/', '%', '==', '!=', '<', '<=', '>', '>=', '&&', '||', '@' ].includes(this);
	}

}

+ Object {
	storeOn2 { |stream| this.storeOn(stream) }

	// might need correction for literals.
	envirKey { | envir |
		^(envir ? currentEnvironment).findKeyForValue(this)
	}

	envirCompileString {
		var key = this.envirKey;
		^if(key.notNil) { "~" ++ key } { this.asCompileString };
	}
}


+ NodeProxy {

	key { | envir |
		^super.envirKey(envir);
	}

	servStr {
		^if(server != Server.default) { "(" ++ server.asCompileString ++")" } { "" }
	}

	// not ideal, but usable for now.
	storeOn2 { | stream |
		var key = this.key;

		if (currentEnvironment.includes(this)) {
			stream << ("~" ++ key)
		} {
			if (key.isNil) {
				stream << "a = NodeProxy.new" ++ this.servStr
			}
		};
	}

	playEditString { |usePlayN, dropDefaults = false, nameStr|
		var editString, outs, amps;
		nameStr = nameStr ?? { this.asCompileString };

		if (nameStr.beginsWith("a = ")) { // anon proxy
			nameStr = nameStr.keep(1);
		};
		usePlayN = usePlayN ?? { if (monitor.notNil) { monitor.usedPlayN } ? false };

		// if they are defaults, don't post them
		if (usePlayN) {
			editString = nameStr ++ this.playNString(dropDefaults)
		} {
			editString = nameStr ++ this.playString(dropDefaults)
		};
		^editString;
	}

	playString { |dropDefaults = false|
		var defOut = 0, defVol = 1, defNumCh = this.numChannels ? 2;
		var out, numCh, vol, setStr = "";

		out = try { this.monitor.out } ? defOut;
		numCh = this.numChannels ? defNumCh; 		// should be able to be different, or not?
		vol = try { this.monitor.vol } ? defVol;

		if (dropDefaults.not or: { out != defOut }) { setStr = setStr ++ "\tout:" + out };

		if (dropDefaults.not or: { numCh != defNumCh }) {
			if (setStr.size > 0) { setStr = setStr ++ ", \n" };
			setStr = setStr ++ "\tnumChannels:" + numCh;
		};

		if (dropDefaults.not or: { vol != defVol }) {
			if (setStr.size > 0) { setStr = setStr ++ ", \n" };
			setStr = setStr ++ "\tvol:" + vol ++ "\n";
		};
		if (setStr.size > 0) {
			setStr = "(\n" ++ setStr ++ "\n)";
		};
		^(".play" ++ setStr ++ ";\n");
	}

	playNString { |dropDefaults = false|
		var numCh =  this.numChannels ? 2;
		var defOuts = { |i| i } ! numCh;
		var defAmps = 1 ! numCh;
		var defIns = { |i| i + this.index } ! numCh;
		var defVol = 1;
		var defFadeTime = 0.02 ! numCh;

		var outs = try { this.monitor.outs } ? defOuts;
		var amps = try { this.monitor.amps } ? defAmps;
		var ins  = try { this.monitor.ins }  ? defIns;
		var vol  = try { this.monitor.vol }  ? defVol;
		var fadeTime = try { this.monitor.fadeTime }  ? defFadeTime;

		var setStr = "";

		// [\o, defOuts, outs, \a, defAmps, amps, \i, defIns, ins].postcs;

		if (dropDefaults.not or: { outs != defOuts }) {
			setStr = setStr ++ "\touts:" + outs
		};
		if (dropDefaults.not or: { amps != defAmps }) {
			if (setStr.size > 0) { setStr = setStr ++ ", \n" };
			setStr = setStr ++ "\tamps:" + amps;
		};
		if (dropDefaults.not or: { ins != defIns }) {
			if (setStr.size > 0) { setStr = setStr ++ ", \n" };
			setStr = setStr ++ "\tins:" + ins;
		};
		if (dropDefaults.not or: { vol != defVol }) {
			if (setStr.size > 0) { setStr = setStr ++ ", \n" };
			setStr = setStr ++ "\tvol:" + vol;
		};
		if (dropDefaults.not or: { fadeTime != defFadeTime }) {
			if (setStr.size > 0) { setStr = setStr ++ ", \n" };
			setStr = setStr ++ "\tfadeTime:" + fadeTime;
		};
		if (setStr.size > 0) {
			setStr = "(\n" ++ setStr ++ "\n)";
		};
		^(".playN" ++ setStr ++ ";\n");
	}

	playNDialog { | bounds, usePlayN |
		var doc = this.playEditString(usePlayN).newTextWindow("edit outs:");
		try { doc.bounds_(bounds) };	// swingosc safe
	}

	findInOpenDocuments { |index = 0|
		var src, str, startSel, doc;
		src = this.at(index);
		src ?? { "NodeProxy: no source at index %.\n".postf(index); ^this };

		str = src.asCompileString;
		doc = Document.allDocuments.detect { |doc|
			startSel = doc.string.find(str);
			startSel.notNil;
		};
		doc !? { doc.front.selectRange(startSel, 0); }
	}

	asCode2 { | includeSettings = true, includeMonitor = true, envir |
		var nameStr, srcStr, str, docStr, indexStr, key;
		var space, spaceCS;
		var specs;

		var isAnon, isSingle, isInCurrent, isOnDefault, isMultiline;

		var hasDefaultNumChannels;

		envir = envir ? currentEnvironment;

		nameStr = envir.use { this.asCompileString };
		indexStr = nameStr;

		if(this.isKindOf(StereoNodeProxy)) {
			if(this.rate == \audio and: {
				(this.numChannels != 2)
			}) {
				hasDefaultNumChannels = false;
			} {
				hasDefaultNumChannels = true;
			};
		} {
			hasDefaultNumChannels = true;
		};

		isAnon = nameStr.beginsWith("a = ");
		isSingle = this.objects.isEmpty or: { this.objects.size == 1 and: { this.objects.indices.first == 0 } };
		isInCurrent = envir.includes(this);
		isOnDefault = server === Server.default;

		//	[\isAnon, isAnon, \isSingle, isSingle, \isInCurrent, isInCurrent, \isOnDefault, isOnDefault].postln;

		space = ProxySpace.findSpace(this);
		spaceCS = try { space.asCode2 } {
			postln("// <could not find a space for proxy: %!>".format(this.asCompileString));
			""
		};

		docStr = String.streamContents { |stream|
			if(includeSettings and: { this.quant.notNil }) {
				stream <<< this << ".quant = " <<< quant << ";\n";
			};

			if(hasDefaultNumChannels.not) {
				stream << nameStr << "." << UGen.methodSelectorForRate(this.rate)
				<< "(" << this.numChannels << ");\n";
			};

			if(isSingle) {
				str = nameStr;
				srcStr = if (this.source.notNil) { this.source.envirCompileString } { "" };

				if ( isAnon ) {			// "a = NodeProxy.new"
					if (isOnDefault.not) { str = str ++ "(" ++ this.server.asCompileString ++ ")" };
					if (srcStr.notEmpty) { str = str ++ ".source_(" ++ srcStr ++ ")" };
				} {
					if (isInCurrent) { 	// ~out
						if (srcStr.notEmpty) { str = str + "=" + srcStr };

					} { 					// Ndef('a') - put sourceString before closing paren.
						if (srcStr.notEmpty) {
							str = str.copy.drop(-1) ++ ", " ++ srcStr ++ nameStr.last
						};
					}
				};
			} {
				// multiple sources
				if (isAnon) {
					str = nameStr ++ ";\n";
					indexStr = "a";
				};

				this.objects.keysValuesDo { |index, item|

					srcStr = item.source.envirCompileString ? "";
					isMultiline = srcStr.includes(Char.nl);
					if (isMultiline) { srcStr = "(" ++ srcStr ++ ")" };
					srcStr = indexStr ++ "[" ++ index ++ "] = " ++ srcStr ++ ";\n";
					str = str ++ srcStr;
				};
			};

			stream << str << if (str.keep(-2).includes($;)) { "\n" } { ";\n" };

			// add settings to compile string
			if(includeSettings) {
				stream << this.nodeMap.asCode2(indexStr, true);
				specs = Halo.at(this);
				if(specs.notNil) {
					specs = specs[\spec];
					if(specs.notNil) {
						specs.keysValuesDo { |key, spec|
							stream <<< this << ".addSpec("
							<<< key << ", " <<< spec << ");\n"
						};
					};
				};
			};
			// include play settings if playing ...
			// hmmm - also keep them if not playing,
			// but inited to something non-default?
			if (this.rate == \audio and: includeMonitor) {
				if (this.monitor.notNil) {
					if (this.isMonitoring) {
						stream << this.playEditString(this.monitor.usedPlayN, true)
					}
				};
			};
		};

		isMultiline = docStr.drop(-1).includes(Char.nl);
		if (isMultiline) { docStr = "(\n" ++ docStr ++ ");\n" };

		^docStr
	}

	document2 { | includeSettings = true, includeMonitor = true |
		var nameStr = this.class.asString ++"_" ++ this.key;
		^this.asCode2(includeSettings, includeMonitor)
		.newTextWindow("document-" ++ nameStr)
	}

}


+ BinaryOpPlug {

	envirCompileString {
		var astr, bstr, opstr, str = "";
		var basic = operator.isBasicOperator;
		astr = a.envirCompileString;
		bstr = b.envirCompileString;

		if(b.isKindOf(AbstractOpPlug)) { bstr = "(%)".format(bstr) };
		opstr = if(basic.not) { ".%(" } { " % " }.format(operator);
		str = str ++ astr ++ opstr ++ bstr;
		if(basic.not) { str = str ++ ")" };
		^str
	}
}

+ UnaryOpPlug {

	envirCompileString {
		^(a.envirCompileString ? "") ++  " "  ++ operator
	}

}



+ ProxySpace {

	// where am I globally accessible?
	asCode2 {
		var key;
		if (this == thisProcess.interpreter.p) { ^"p" };
		if (this == currentEnvironment) { ^"currentEnvironment" };
		if (Ndef.all.includes(this)) {
			key = Ndef.all.findKeyForValue(this);
			^"Ndef.all[%]".format(key.asCompileString);
		};
		if (ProxySpace.all.includes(this)) {
			key = ProxySpace.all.findKeyForValue(this);
			^"ProxySpace.all[%]".format(key.asCompileString);
		};

		^"/***( cannot locate this proxyspace )***/"
	}

	storeOn2 { | stream, keys, includeSettings = true, includeMonitors = true |
		var proxies, hasGlobalClock;

		hasGlobalClock = clock.isKindOf(TempoBusClock);

		stream << "\n(\n";
		if(hasGlobalClock) { stream <<< this.asCode2 << ".makeTempoClock(" << clock.tempo << ");\n\n"; };
		// find keys for all parents
		if(keys.notNil) {
			proxies = IdentitySet.new;
			keys.do { |key| var p = envir[key]; p !? { p.getFamily(proxies) } };
			keys = proxies.collect { |item| item.key(envir) };
		} { keys = envir.keys };

		if(hasGlobalClock) { keys.remove(\tempo) };

		// add all objects to compilestring
		keys.do { |key|
			var proxy = envir.at(key);
			stream << proxy.asCode2(includeSettings, includeMonitors, this.envir) << "\n";
		};

		stream << ");\n";
	}

	documentOutput2 {
		^this.document2(nil, true)
	}

	document2 { | keys, onlyAudibleOutput = false, includeSettings = true |
		var str;
		if(onlyAudibleOutput) {
			keys = this.monitors.collect { |item| item.key(envir) };
		};
		str = String.streamContents { |stream|
			stream << "// ( p = %.new(s).push; ) \n\n".format(this.class.name);
			this.storeOn2(stream, keys, includeSettings);
			//			this.do { |px| if(px.monitorGroup.isPlaying) {
			//				stream << px.playEditString << ".play; \n"
			//				}
			//			};
		};
		// ^str.newTextWindow((name ? "proxyspace").asString)
		^Document((name ? "proxyspace").asString, str);
	}

}


+ ProxyNodeMap {

	asCode2 { | namestring = "", dropOut = false |
		^String.streamContents({ |stream|
			var map, rate;
			if(dropOut) {
				map = this.copy;
				map.removeAt(\out);
				map.removeAt(\i_out);
			} { map = this };

			if(map.notEmpty) {
				// 'map' might refer to other NodeProxies
				// Before referring to them in an arg list,
				// we should be sure they are initialized
				// to the right rate and number of channels.
				// It is OK if the 'source' definition comes later in the doc string.
				map.keysValuesDo { |key, value|
					if(value.isKindOf(BusPlug)) {
						rate = value.rate;
						if(rate != \audio) { rate = \control };
						stream <<< value  // storeOn2 gets the ~key
						<< "." << UGen.methodSelectorForRate(rate)
						<< "(" << value.numChannels << ");\n";
					};
				};
				stream << namestring << ".set(" <<<* map.asKeyValuePairs << ");" << Char.nl;
			};
			if(rates.notNil) {
				stream << namestring << ".setRates(" <<<* rates << ");" << Char.nl;
			}
		});
	}
}
