
JMLocalMap {
	var <decomp;
	var <localInChannels, <localOutChannels;
	var <proxyIndices;

	*new { |decomp|
		^super.new.init(decomp)
	}

	init { |argDecomp|
		decomp = argDecomp;
		localInChannels = IdentityDictionary[
			\audio -> 0, \control -> 0
		];
		localOutChannels = IdentityDictionary.new;  // rate -> array of JMMapStrings
		// key -> local IO index
		proxyIndices = IdentityDictionary.new;
		// this.scanLocalPairs;
		// this.checkForwardReferences;
	}

	scanLocalPairs {
		var cstr;
		decomp.nodeOrder.do { |key|
			var proxy = decomp.proxyspace[key];
			if(proxy.objects[0].isKindOf(SynthDefControl)) {
				proxy.objects[0].synthDef.children.do { |ugen|
					case
					{ ugen.isKindOf(LocalIn) } {
						ugen.channels.do { |chan|
							cstr = JMMapString(
								chan,
								localInChannels[chan.rate],  // num channels so far
								this.localInString(chan.rate)
							).setFlag(\multiChannel, true);
							// ^^ local ins are always multichannel (render with .asArray)
							localInChannels[chan.rate] = localInChannels[chan.rate] + 1;
							decomp.outputProxies[chan] = cstr;
						};
					}
					{ ugen.isKindOf(LocalOut) } {
						ugen.inputs.do { |chan, i|
							cstr = CollStream.new;
							chan.streamAsInputUGen(key, cstr);
							localOutChannels[chan.rate] = localOutChannels[chan.rate].add(
								JMMapString(
									chan,
									0,
									cstr.collection
								).setFlag(\multiChannel, false);
								// ^^ bc streamAsInputUGen should handle [0] if needed
							);
						};
					}
				};
			};
		};
	}

	addLocalIO { |srcAssn|
		var index = proxyIndices[srcAssn.key];
		var rate, size;
		if(index.notNil) { ^index };
		rate = srcAssn.value.rate;
		index = localOutChannels[rate].size;
		proxyIndices[srcAssn.key] = index;
		size = srcAssn.value.numChannels;
		localInChannels[rate] = localInChannels[rate] + size;
		size.do { |i|
			localOutChannels[rate] = localOutChannels[rate].add(
				JMMapString(
					nil,  // channel but seems unused
					i,
					"~" ++ srcAssn.key ++ "Out"
				).setFlag(\multiChannel, size >= 2)
			);
		};
		^index
	}

	localInString { |rate|
		var selector = UGen.methodSelectorForRate(rate).asString;
		selector[0] = selector[0].toUpper;
		^"localIn" ++ selector
	}

	streamLocalIn { |stream|
		if(localInChannels[\audio] > 0) {
			stream << "\tvar localInAr = LocalIn.ar("
			<< localInChannels[\audio]
			<< ").asArray;\n";
		};
		if(localInChannels[\control] > 0) {
			stream << "\tvar localInKr = LocalIn.kr("
			<< localInChannels[\control]
			<< ").asArray;\n";
		};
	}

	streamLocalOut { |stream|
		if(localOutChannels[\audio].size > 0) {
			stream << "\tLocalOut.ar([";
			localOutChannels[\audio].do { |cstr, i|
				if(i > 0) { stream << ", " };
				stream << cstr.string;
			};
			stream << "]);\n";
		};
		if(localOutChannels[\control].size > 0) {
			stream << "\tLocalOut.kr([";
			localOutChannels[\control].do { |cstr, i|
				if(i > 0) { stream << ", " };
				stream << cstr.string;
			};
			stream << "]);\n";
		};
	}
}

JMDecompiler {
	var <patch, <connections, <inputMap, <synthdefs, <localMap;
	var lambdaEnvir, <nodeOrder;
	var stream;
	var <>proxyIO;
	var <>allControlNames, <>outputProxies;

	*new { |patch|
		^super.newCopyArgs(patch).init;
	}

	init {
		// ordering, needed
		connections = this.getChains;
		this.orderNodes;

		proxyIO = IdentityDictionary.new;
		outputProxies = IdentityDictionary.new;
		localMap = JMLocalMap(this);
		nodeOrder.do { |key|
			var new = JMProxyIO(key, this);
			// isNil if it's not a SynthDefControl
			// e.g. JMBufferRefs or fixed values
			if(new.notNil) {
				proxyIO.put(key, new);
				this.addIO(new);
			};
		};
		localMap.scanLocalPairs;
	}

	proxyspace { ^patch.proxyspace }

	orderNodes {
		var traverse = { |key|
			var conn = connections[key];
			if(conn[\resolved].not) {
				// for each, hit antecedents first
				conn[\antecedents].do { |antecedent|
					// in a feedback loop, the antecedent will have the descendant
					// as its own antecedent
					// avoid infinite recursion by breaking the chain
					connections[antecedent].antecedents.remove(key);
					traverse.(antecedent);
				};
				nodeOrder = nodeOrder.add(key);
				conn[\resolved] = true;
			};
		};

		this.proxyspace.keysValuesDo(traverse);
		^nodeOrder
	}

	// I think I don't need constant proxies here
	// bc they will render as NamedControl, no need to make an antecedent
	getChains {
		var conn = patch.getConnections;
		var proxies = patch.proxyspace.envir.collect { |value, key|
			(antecedents: IdentitySet.new, descendants: IdentitySet.new, resolved: false)
		};

		conn.do { |chain|
			chain.do { |conn|
				var srcKey = patch.proxyspace.findKeyForValue(conn.src);
				var targKey = patch.proxyspace.findKeyForValue(conn.target);
				proxies[srcKey].descendants.add(targKey);
				proxies[targKey].antecedents.add(srcKey);
			}
		};

		// references may also be In units produced by ~xyz.ar or .kr
		this.proxyspace.keysValuesDo { |key, proxy|
			var source, srcKey;
			if(proxy.objects[0].isKindOf(SynthDefControl)) {
				proxy.objects[0].synthDef.children.do { |ugen|
					if(ugen.isKindOf(AbstractIn)) {
						source = this.findProxyForBus(
							// this.proxyspace,
							Bus(
								ugen.rate,
								ugen.inputs[0],
								ugen.numOutputs,
								patch.server
							)
						);
						if(source.notNil) {
							proxies[source.key].descendants.add(key);
							proxies[key].antecedents.add(source.key);
						};
					};
				};
			};
		};

		^proxies
	}

	findProxyForBus { | /*proxyspace,*/ bus|
		this.proxyspace.keysValuesDo { |key, proxy|
			if(proxy.bus.notNil and: {
				proxy.rate == bus.rate and: {
					proxy.bus.index == bus.index
				}
			}) {
				^this.proxyspace.envir.associationAt(key)
			}
		};
		^nil
	}

	findControlName { |name|
		^allControlNames.detect { |assn|
			assn.key.name == name
		};
	}

	findControlSource { |name, index = 0|
		var cn = this.findControlName;
		^if(cn.notNil) {
			outputProxies[cn.value[index]]
		};  // else nil
	}

	addIO { |proxyio|
		var cn;
		proxyio.controls.do { |assn|
			cn = allControlNames.detect { |cn|
				cn.key.name == assn.key.name
			};
			if(cn.notNil) {  // merge
				cn.value = cn.value.extend(max(cn.value.size, assn.value.size));
				assn.value.do { |chan, j|
					if(cn.value[j].isNil) {
						cn.value[j] = chan;
					};
				};
			} {  // else all new
				allControlNames = allControlNames.add(assn);
			};
		};
		proxyio.channelSources.keysValuesDo { |chan, str|
			outputProxies[chan] = str;
		};
	}

	streamCode { |argStream(Post)|
		stream = argStream;
		stream << "\tvar env = Environment.new;\n\tenv.use {\n";
		localMap.streamLocalIn(stream);
		nodeOrder.do { |key|
			this.postUGens(key, stream);
		};
		localMap.streamLocalOut(stream);
		stream << "\t};\n";
	}

	postUGens { |key, stream|
		var proxy = patch.proxyspace[key];
		var def;
		var numOutputs;
		var ugen;
		var cname, value;
		var str;
		case
		{ proxy.objects[0].isKindOf(SynthDefControl) } {
			def = proxy.objects[0].synthDef;
			numOutputs = def.children.last.inputs.size - 1;
			(0 .. def.children.size - 6 - numOutputs).do { |i|
				ugen = def.children[i];
				if(ugen.decompilerCanOptimizeOut(key, this).not) {
					stream << "\t~" << key << "_" << i << " = ";
					ugen.streamCode(key, stream, this);
					stream << ";\n";
				};
			};
			// the Out might expand mono to stereo, for instance
			stream << "\t~" << key << "Out" << " = ";
			ugen = def.children.last.inputs.drop(1);
			if(ugen.size > 1) {
				stream << "[";
			};
			ugen.do { |chan, i|
				if(i > 0) { stream << ", " };
				// in a StereoProxySynthDef, the Out channels
				// are all aChannel * EnvGen
				// we need to strip out the xfade envelope
				// might be UGen, so strip that out too
				// and replace with variable reference
				chan = chan.inputs[0];
				str = proxyIO[key].channelSources[chan];
				if(str.notNil) {
					stream << str.string
				} {
					stream << "~" << key << "_" << chan.synthIndex;
					if(chan.isKindOf(OutputProxy)) {
						if(chan.source.channels.size > 1) {
							stream << "[" << chan.outputIndex << "]"
						}
					};
				};
			};
			if(ugen.size > 1) {
				stream << "]";
			};
			stream << ";\n";
		}
		{ #[noncontrol, scalar].includes(proxy.source.rate) } {
			stream << "\t~" << key << "Out = NamedControl."
			<< UGen.methodSelectorForRate(proxy.rate)
			<< "(" <<< key << ", ";
			// note, there's no 'nodeMap' value for this type of proxy
			value = proxy.source.asControlInput ?? {
				Array.fill(proxy.numChannels, 0)
			};
			if(value.size < 2) {
				stream <<< value
			} {
				stream << "[";
				value.do { |val, i|
					if(i > 0) { stream << ", " };
					stream <<< val;
				};
				stream << "]";
			};
			stream << ");\n";
		}
	}
}

JMProxyIO {
	var <>key, <>proxy;
	var <>inputs;  // array of outputproxy --> map string? or nil if unmapped
	var <>outputs;  // array of ugens
	var <>controls;  // array, controlname --> [outputproxy, ...]
	var <>controlUGens;  // dict: Control unit --> [cname, cname...]
	var <>channelSources;
	var <>inUGensAsControls;  // e.g. ~wt.kr(1) to refer to a noncontrol value
	var <>synthDef;
	var <>decomp;
	var <>controlPairs;  // Control UGen --> array of associations name --> value

	*new { |key, decomp|
		^super.new.init(key, decomp)
	}

	init { |argKey, argDecomp|
		decomp = argDecomp;
		key = argKey;
		proxy = decomp.proxyspace[key];

		synthDef = proxy.synthDef;
		if(synthDef.isNil) { ^nil };

		controls = Array.new;
		controlUGens = IdentityDictionary.new;
		controlPairs = IdentityDictionary.new;
		this.collectControls;
		outputs = this.scanOutputs;
		channelSources = IdentityDictionary.new;
		this.findControlSources;
		inUGensAsControls = IdentityDictionary.new;
		this.scanInputs;
	}

	collectControls {
		var cnIndex = -1, cn, index = 0;
		var cnSize, cnChannelIndex, ugenChannelIndex;
		var src;

		var findControlName = {
			// index = index + 1;
			cnIndex = cnIndex + 1;
			cn = synthDef.allControlNames[cnIndex];
			if(cn.isNil or: { cn.index != index }) {
				cnIndex = synthDef.allControlNames.detectIndex { |cn|
					cn.index == index
				};
				if(cnIndex.isNil) {
					// Error("ControlName could not be found for index" + index).throw
					cn = nil;
				} {
					cn = synthDef.allControlNames[cnIndex];
				};
			};
			cnChannelIndex = 0;
			if(cn.notNil) {
				cnSize = max(1, cn.defaultValue.size);
			} {
				cnSize = 1;
			};
		};

		findControlName.();

		synthDef.children.do { |ugen, i|
			if(ugen.class.isControlUGen) {
				ugenChannelIndex = 0;
				ugen.channels.do { |chan, j|
					this.addControl(cn, cnChannelIndex, chan);
					index = index + 1;
					cnChannelIndex = cnChannelIndex + 1;
					if(cnChannelIndex == cnSize, findControlName);
					ugenChannelIndex = ugenChannelIndex + 1;
				};
			};
		}
	}

	addControl { |controlName, chanIndex, chan|
		var ctl;
		var lastCtlName = controls.last.tryPerform(\key).tryPerform(\name);
		if((lastCtlName == controlName.name) or: {
			controlName.name == '?'
		}) {  // arrayed
			ctl = controls.last;
			ctl.value = ctl.value.add(chan);
		} {
			controls = controls.add(controlName -> [chan]);
			controlUGens[chan.source] = controlUGens[chan.source].add(controlName);
		};
	}

	scanOutputs {
		var ctl = controls.detect { |assn|
			assn.key.name == \out
		};
		var ugen = synthDef.children.detect { |ugen|
			ugen.class.isOutputUGen and: {
				// assuming that \out is a single-value control
				// which it should always be in a SynthDefControl
				ugen.inputs.includes(ctl.value[0])
			}
		};
		var out;
		if(ugen.isNil) {
			Error("Proxy % Out UGen can't be found".format(key)).throw;
		};
		(1 .. ugen.inputs.size - 1).do { |i|
			var src = ugen.inputs[i];
			if(src.isKindOf(BinaryOpUGen) and: { src.operator == '*' }) {
				out = out.add(src.inputs[0]);  // resolve to x * envgen input
			} {
				out = out.add(src);  // this shouldn't happen though
			}
		};
		^out
	}

	findControlSources {
		var prevUGen;
		var indexOffset;
		// say you have a two-channel Control UGen
		// but one of the channels is mapped to another source
		// then source.channels.size > 1 but in the output code,
		// it will be single channel
		// and you don't know that until after all the channels have been checked
		// so we have to keep a count and do another pass at the end to update multiChannel
		var numChannelsPerUGen = IdentityDictionary.new;

		// key.debug("\n\nfindControlSources");
		controls.do { |assn|  // controlname -> array of channels
			// deleted channels from Control A should not affect B
			if(assn.value[0].source !== prevUGen) {
				prevUGen = assn.value[0].source;
				indexOffset = 0;
			};
			block { |break|
				var src, localIndex, str;

				// assn.debug("checking");

				// is it mapped?
				// note, by doing this check first, we support e.g. 'in'
				// mapped differently in different proxies
				src = this.mapSource(assn);  // key -> source proxy
				if(src.notNil) {
					if(this.isForwardReference(src, assn)) {
						localIndex = this.addLocalIO(src, assn);
						str = decomp.localMap.localInString(assn.value[0].rate);
						assn.value.do { |chan, i|
							channelSources[chan] = JMMapString(
								// chan,
								src.value.findOutputChannel(i),
								i + localIndex,
								str
							).setFlag(\multiChannel, true);
						};
					} {
						// normal case: link to earlier proxy's output
						assn.value.do { |chan, i|
							channelSources[chan] = JMMapString(
								// chan,
								// protect against multiChannel getting clobbered below
								src.value.findOutputChannel(i),
								i,
								"~" ++ src.key ++ "Out"
							).setFlag(\multiChannel, src.value.numChannels >= 2);
						};
					};
					// these Control channels have a source: delete from Control list
					// so all future indices into this Control have to shift down
					indexOffset = indexOffset + max(1, assn.value.size);
					// indexOffset.debug("indexOffset from mapping");
					break.();
				};

				// is it pre-existing?
				// what about channel indices -- I think using the cn array is OK
				src = decomp.findControlName(assn.key.name);
				// src.debug("findControlName");
				if(src.notNil) {
					assn.value.do { |chan, i|
						channelSources[chan] = decomp.outputProxies[
							src.value[i]
						]
						// the original JMMapString is set to renderControl
						// but this one should NOT render
						.copy.setFlag(\renderControl, false);
					};
					indexOffset = indexOffset + max(1, assn.value.size);
					// indexOffset.debug("indexOffset from pre-existing");

					break.();
				};

				// otherwise, make new control channel reference
				assn.value.do { |chan|
					channelSources[chan] = JMMapString(
						chan, (chan.outputIndex - indexOffset),
						"~" ++ key ++ "_" ++ chan.source.synthIndex
					).setFlag(\renderControl);
					if(numChannelsPerUGen[prevUGen].isNil) {
						numChannelsPerUGen[prevUGen] = max(1, assn.value.size)
					} {
						numChannelsPerUGen[prevUGen] = numChannelsPerUGen[prevUGen] + max(1, assn.value.size);
					};
				};
			};
		};

		numChannelsPerUGen.keysValuesDo { |ugen, count|
			var mc = count >= 2;
			ugen.channels.do { |chan|
				var str = channelSources[chan];
				if(str.chan === chan) {
					str.setFlag(\multiChannel, mc)
				}
			};
		};
	}

	mapSource { |assn|
		var ctlValue = proxy.nodeMap[assn.key.name/*.debug("looking up nodeMap")*/]/*.debug("got")*/;
		^if(ctlValue.isKindOf(BusPlug) and: {
			ctlValue.rate == assn.value[0].rate
		}) {
			decomp.proxyspace.findKeyForValue(ctlValue)
			->
			ctlValue  // the source nodeproxy
		};  // else nil
	}

	// src = (key -> proxy)
	// assn = (control name -> array of outputproxies) unused
	isForwardReference { |src/*, assn*/|
		var srcIndex, myIndex;
		block { |break|
			decomp.nodeOrder.do { |item, i|
				if(srcIndex.isNil and: { src.key == item }) {
					srcIndex = i;
					if(myIndex.notNil) { break.() };
				};
				if(myIndex.isNil and: { key == item }) {
					myIndex = i;
					if(srcIndex.notNil) { break.() };
				};
			};
		};
		// can't check, assume no problem lol
		if(srcIndex.isNil or: { myIndex.isNil }) { ^false };
		^srcIndex >= myIndex
	}

	// localIndex = this.addLocalChannel(src, assn);
	// src = (key -> proxy)
	addLocalIO { |src, assn|
		^decomp.localMap.addLocalIO(src)
	}

	scanInputs {
		synthDef.children.do { |ugen, i|
			var bus, src, localIndex, str, val;
			if(ugen.class.isInputUGen) {
				bus = Bus(ugen.rate, ugen.inputs[0], ugen.channels.size, proxy.server);
				src = decomp.findProxyForBus(bus);  // key -> proxy
				if(src.notNil) {
					case
					{ this.isForwardReference(src) } {
						localIndex = this.addLocalIO(src);
						str = decomp.localMap.localInString(ugen.rate);
						ugen.channels.do { |chan, j|
							channelSources[chan] = JMMapString(
								chan,
								localIndex + j,
								str
							).setFlag(\multiChannel, true);
						};
					}
					{  // was there another case? this is normal backward reference
						ugen.channels.do { |chan, j|
							channelSources[chan] = JMMapString(
								chan,
								j,
								"~" ++ src.key ++ "Out"
							).setFlag(\multiChannel, src.value.numChannels >= 2);
						};
					};
				};
				// else: free-standing In units do not get JMMapStrings
			};
		};
	}

	stringsForName { |name|
		var ctl = controls.detect { |assn| assn.key.name == name };
		^if(ctl.notNil) {
			ctl.value.collect { |chan| channelSources[chan] }
		}  // else nil
	}
}

JMMapString {
	var <>chan, <>index, string, <>flags;

	*new { |chan, index = 0, string = ""|
		^super.newCopyArgs(chan, index, string).init
	}

	init {
		flags = IdentitySet.new;
	}

	setFlag { |flag, bool = true|
		if(bool) {
			flags.add(flag)
		} {
			flags.remove(flag)
		}
	}

	hasFlag { |flag| ^flags.includes(flag) }

	baseString { ^string }

	string {
		if(this.hasFlag(\multiChannel)) {
			^string ++ "[" ++ index ++ "]"
		} {
			^string
		};
	}

	// override, use with caution
	string_ { |argString|
		string = argString;
	}

	copy { ^this.deepCopy }
}
