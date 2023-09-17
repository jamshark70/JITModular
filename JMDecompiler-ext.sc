+ UGen {
	streamCode { |key, stream, decomp|
		this.streamNameAndRate(stream);
		stream << "(";
		this.streamInputs(key, stream, decomp);
		stream << ")";
	}

	streamNameAndRate { |stream|
		stream << this.class.name
		<< "." << UGen.methodSelectorForRate(this.rate);
	}

	streamInputs { |key, stream, decomp|
		if(this.inputs.size > 0) {
			this.inputs.do { |input, j|
				if(j > 0) { stream << ", " };
				input.streamAsInputUGen(key, stream, decomp)
			};
		};
	}

	streamAsInputUGen { |key, stream|
		stream << key << "_" << this.synthIndex;
	}

	// assume we have to render it
	decompilerCanOptimizeOut { ^false }
}

// check in decomp whether this belongs to a Control that's already been written
// streamAsInputUGen no no no, decomp inputMap knows the string
+ OutputProxy {
	streamAsInputUGen { |key, stream, decomp|
		var str = decomp.outputProxies[this];
		if(str.notNil) {
			stream << str.string;  // .asString(this);
		} {
			stream << key << "_" << this.source.synthIndex;
			if(this.source.channels.size >= 2) {
				stream << "[" << this.outputIndex << "]"
			};
		}
	}
}

+ Object {
	streamAsInputUGen { |key, stream|
		stream << this.asControlInput
	}
}

+ SequenceableCollection {
	streamAsInputUGen { |key, stream, decomp|
		stream << "[";
		this.do { |item, i|
			if(i > 0) { stream << ", " };
			item.streamAsInputUGen(key, stream, decomp)
		};
		stream << "]";
	}
}

+ UnaryOpUGen {
	streamCode { |key, stream, decomp|
		stream << this.operator << "(";
		this.inputs[0].streamAsInputUGen(key, stream, decomp);
		stream << ")";
	}
}

+ BinaryOpUGen {
	streamCode { |key, stream, decomp|
		stream << "(";
		this.inputs[0].streamAsInputUGen(key, stream, decomp);
		stream << " " << this.operator;
		if(this.operator.asString[0].isAlpha) {
			stream << ":"
		};
		stream << " ";
		this.inputs[1].streamAsInputUGen(key, stream, decomp);
		stream << ")";
	}
}

// cases:
// control mapped to kr proxy: source key ++ lastIndex
//   - remove from Control.names
//   - shift outputproxies (in `channels`)
// control already resolved in another proxy
//   - remove from Control.names
//   - shift outputproxies (in `channels`)
// arrayed controls: untested
// unmapped control: render in Control.names
// btw Control.names doesn't work for arrayed controls
// maybe have to concatenate NamedControls for those?
+ Control {
	streamCode { |key, stream, decomp|
		var contents = this.getPairs(key, decomp);
		case
		// we should now never reach this branch
		// { contents.size == 0 } {
		// 	stream << "0 /* Control UGen is fully mapped, removed */";  // dummy value, optimize out later?
		// }
		{ contents.size == 1 and: {
			contents[0].value.size > 1
		} } {
			// case: only 1 control name, multiple values:
			// NamedControl.kr(name, [...]);
			stream << "NamedControl."
			<<
			switch(this.class)
			{ Control } { "kr" }
			{ TrigControl } { "tr" }
			<< "("
			<<< if(contents[0].key == \gt) { \gate } { contents[0].key }
			<< ", [";
			contents[0].value.do { |val, i|
				if(i > 0) { stream << ", " };
				stream << val;
			};
			stream << "])";
		} {
			// multiple names

			stream << this.class.name
			<< ".names([";
			contents.do { |assn, i|
				if(i > 0) { stream << ", " };
				stream <<< if(assn.key == \gt) { \gate } { assn.key };
				// if any pairs have arrayed values
				// then it's an "arg xyz = #[1, 2, 3]" situation
				// Control.names needs placeholders for the array vals
				max(0, assn.value.size - 1).do {
					stream << ", '?'"
				};
			};
			stream << "])."
			<< /* (switch(this.class)
				{ Control } { */ "kr" /* }
				{ TrigControl } { "tr" }) */
			<< "([";
			contents.do { |assn, i|
				if(i > 0) { stream << ", " };
				assn.value.asArray.do { |item, j|
					if(j > 0) { stream << ", " };
					stream <<< item;
				};
			};
			stream << "])";
			// SynthDef itself does a "reshapeLike" here
			// but this way of interpreting the Control objects
			// requires a flat list of output channels
			// so we skip the reshape
		};
	}

	getPairs { |key, decomp|
		var io = decomp.proxyIO[key];
		var cnames, out, index;
		var nodemap;

		out = io.controlPairs[this];
		if(out.notNil) { ^out };

		cnames = io.controlUGens[this];
		out = List(cnames.size);
		// this '==' check is really '==='
		// it "should" be OK because the ControlName objects in 'controls' and 'controlUGens'
		// are pulled directly from the real synthdef
		index = io.controls.detectIndex { |assn| assn.key == cnames[0] };
		nodemap = decomp.proxyspace[key].nodeMap;

		// keep names and default values only for \renderControl channels
		cnames.do { |cn, j|
			var value;
			if(io.controls[index].value.any { |outputproxy|
				io.channelSources[outputproxy].hasFlag(\renderControl)
			}) {
				if(nodemap.notNil) {  // should always be non-nil?
					value = nodemap[cn.name] ?? { cn.defaultValue };
				} {
					value = cn.defaultValue;
				};
				out = out.add((cn.name -> value));
			};
			index = index + 1;
		};

		// if we got this far, we know it wasn't populated before
		io.controlPairs[this] = out;

		^out
	}

	decompilerCanOptimizeOut { |key, decomp|
		^this.getPairs(key, decomp).size == 0
	}
}

+ AudioControl {
	streamCode { |key, stream, decomp|
		var name, i, io, cname;
		stream << "NamedControl.ar(";
		io = decomp.proxyIO[key];
		cname = io.controlUGens[this];
		// JMInput should have just one name;
		// maybe have to fix this later for a_ args
		if(cname.isNil) {
			Error("No ControlName available for AudioControl").throw;
		};
		stream << "'" << key << "_" << cname[0].name
		<< "', " <<< cname[0].defaultValue;
		stream << ")";
	}

	decompilerCanOptimizeOut { |key, decomp|
		var io = decomp.proxyIO[key];
		^channels.every { |chan|
			var self;
			io.channelSources[chan].notNil and: {
				(key ++ "_" ++ chan.synthIndex) != io.channelSources[chan].baseString
			}
		};
	}
}

// all In units should go through here
// because: either it's resolved to another module
// or it's not -- if not, normal syntax applies
// (LocalIn has a different syntax but it should *always* be resolved in JITMod)
+ AbstractIn {
	streamCode { |key, stream, decomp|
		var str = decomp.outputProxies[channels[0]];
		if(str.notNil) {
			if(channels.size > 1) {
				stream << "[";
			};
			channels.do { |pr, i|
				if(i > 0) { stream << ", " };
				pr.streamAsInputUGen(key, stream, decomp);
			};
			if(channels.size > 1) {
				stream << "]";
			};
		} {
			this.streamNameAndRate(stream);
			stream << "(";
			this.streamInputs(key, stream, decomp);
			stream << ", " << channels.size << ")";
		}
	}

	decompilerCanOptimizeOut { |key, decomp|
		// var io = decomp.proxyIO[key];
		// "free-standing In units do not get JMMapStrings"
		// so, if all are accounted for, then optimization is allowed
		// LocalIn channels get added into decomp, but not proxyIO
		// this might be a bug but I won't fix it today
		^channels.every { |pr|
			decomp.outputProxies[pr].notNil
		};
	}
}

+ LocalOut {
	// this should never happen bc of decompilerCanOptimizeOut
	// streamCode { |key, stream, decomp|
	// 	stream << "0 /* LocalOut channels are collected at the end */";
	// }

	// should always omit
	decompilerCanOptimizeOut { ^true }
}

+ NodeProxy {
	synthDef { |index = 0|
		var obj = objects[index];
		^if(obj.isKindOf(SynthDefControl)) {
			obj.synthDef
		}  // else nil
	}

	lastUGenIndex {
		var def;
		^DeprecatedError(this, thisMethod,
			this.class.findMethod(\findOutputChannel)
		);
	}

	findOutputChannel { |index|
		// ProxySynthDef wraps in an envelope, which we want to discard.
		// so trace back: Output channel source = '*'
		// '*' source = raw channel
		var def;
		var ugen;
		if(objects[0].isKindOf(SynthDefControl)) {
			def = objects[0].synthDef;
			ugen = def.children.last;
			// trace up one
			ugen = ugen.inputs[1 + index];
			if(ugen.isNil) { ^nil };
			ugen = ugen.inputs[0];
			// refactor later
			^(if(ugen.isKindOf(OutputProxy)) {
				[ugen.synthIndex, ugen.outputIndex]
			} {
				[ugen.synthIndex, nil]
			})
		} {
			^nil
		}
	}
}


// specific UGen cases
+ Sum3 {
	streamCode { |key, stream, decomp|
		stream << this.class.name;
		if(this.inputs.size > 0) {
			stream << "(";
			this.inputs.do { |input, j|
				if(j > 0) { stream << ", " };
				input.streamAsInputUGen(key, stream, decomp)
			};
			stream << ")";
		};
	}
}

+ Sum4 {
	streamCode { |key, stream, decomp|
		stream << this.class.name;
		if(this.inputs.size > 0) {
			stream << "(";
			this.inputs.do { |input, j|
				if(j > 0) { stream << ", " };
				input.streamAsInputUGen(key, stream, decomp)
			};
			stream << ")";
		};
	}
}

+ MulAdd {
	// maybe switch back to this one?
	// streamCode { |key, stream, decomp|
	// 	stream << "(";
	// 	inputs[0].streamAsInputUGen(key, stream, decomp);
	// 	stream << " * ";
	// 	inputs[1].streamAsInputUGen(key, stream, decomp);
	// 	stream << " + ";
	// 	inputs[2].streamAsInputUGen(key, stream, decomp);
	// 	stream << ")";
	// }

	streamCode { |key, stream, decomp|
		stream << this.class.name;
		if(this.inputs.size > 0) {
			stream << "(";
			this.inputs.do { |input, j|
				if(j > 0) { stream << ", " };
				input.streamAsInputUGen(key, stream, decomp)
			};
			stream << ")";
		};
	}
}

+ Select {
	streamCode { |key, stream, decomp|
		this.streamNameAndRate(stream);
		stream << "(";
		inputs[0].streamAsInputUGen(key, stream, decomp);
		stream << ", [";
		(1 .. inputs.size - 1).do { |i|
			if(i > 1) { stream << ", " };
			inputs[i].streamAsInputUGen(key, stream, decomp);
		};
		stream << "])";
	}
}

+ EnvGen {
	streamCode { |key, stream, decomp|
		this.streamNameAndRate(stream);
		stream << "(Env([";
		inputs[5].streamAsInputUGen(key, stream, decomp);
		// levels
		(9, 13 .. inputs.size-1).do { |i|
			stream << ", ";
			inputs[i].streamAsInputUGen(key, stream, decomp);
		};
		stream << "], [";
		// times
		(10, 14 .. inputs.size-1).do { |i|
			if(i > 10) { stream << ", " };
			inputs[i].streamAsInputUGen(key, stream, decomp);
		};
		stream << "], [";
		// curves
		(11, 15 .. inputs.size-1).do { |i|
			var shapeName;
			if(i > 11) { stream << ", " };
			shapeName = Env.shapeNames.findKeyForValue(inputs[i]);
			if(shapeName.notNil) {
				stream <<< shapeName;
			} {
				inputs[i+1].streamAsInputUGen(key, stream, decomp);
			};
		};
		stream << "], ";
		// releaseNode, loopNode
		inputs[7].streamAsInputUGen(key, stream, decomp);
		stream << ", ";
		inputs[8].streamAsInputUGen(key, stream, decomp);
		stream << ")";
		4.do { |i|
			stream << ", ";
			inputs[i].streamAsInputUGen(key, stream, decomp);
		};
		stream << ", ";
		// doneAction: assume ~eg is the main envelope
		if(key == \eg) {
			stream << "2"
		} {
			inputs[4].streamAsInputUGen(key, stream, decomp);
		};
		stream << ")";
	}
}

+ InfoUGenBase {
	streamCode { |key, stream, decomp|
		stream << this.class.name << ".ir"
	}
}

+ SendReply {
	streamCode { |key, stream, decomp|
		var numValues;
		this.streamNameAndRate(stream);
		stream << "(";
		inputs[0].streamAsInputUGen(key, stream, decomp);
		stream << ", '";
		inputs[2].do { |i|
			if(inputs[3+i] == 39) { stream << "\\" };  // escape single quote
			stream << inputs[3+i].asAscii;
		};
		stream << "', ";
		// 3+inputs[2] --> first value. Num values = inputs.size - this
		numValues = inputs.size - 3 - inputs[2];
		if(numValues > 1) {
			stream << "[";
		};
		numValues.do { |i|
			if(i > 0) { stream << ", " };
			inputs[3 + inputs[2] + i].streamAsInputUGen(key, stream, decomp);
		};
		if(numValues > 1) {
			stream << "]";
		};
		stream << ", ";
		// replyID
		inputs[1].streamAsInputUGen(key, stream, decomp);
		stream << ")";
	}
}

+ SendPeakRMS {
	streamCode { |key, stream, decomp|
		var numValues, numChars;
		this.streamNameAndRate(stream);
		stream << "(";
		inputs[0].streamAsInputUGen(key, stream, decomp);  // replyRate
		stream << ", ";
		inputs[1].streamAsInputUGen(key, stream, decomp);  // peakLag
		numValues = inputs[3];
		numChars = inputs[4 + numValues];
		stream << ", '";
		numChars.do { |i|
			if(inputs[5 + numValues + i] == 39) { stream << "\\" };  // escape single quote
			stream << inputs[5 + numValues + i].asAscii;
		};
		stream << "', ";
		if(numValues > 1) {
			stream << "[";
		};
		numValues.do { |i|
			if(i > 0) { stream << ", " };
			inputs[4 + i].streamAsInputUGen(key, stream, decomp);
		};
		if(numValues > 1) {
			stream << "]";
		};
		stream << ", ";
		// replyID
		inputs[2].streamAsInputUGen(key, stream, decomp);
		stream << ")";
	}
}

+ Poll {
	streamCode { |key, stream, decomp|
		var numValues;
		this.streamNameAndRate(stream);
		stream << "(";
		inputs[0].streamAsInputUGen(key, stream, decomp);  // trig
		stream << ", ";
		inputs[1].streamAsInputUGen(key, stream, decomp);  // input
		stream << ", \"";
		inputs[3].do { |i|
			if(inputs[4+i] == 34) { stream << "\\" };  // escape single quote
			stream << inputs[4+i].asAscii;
		};
		stream << "\", ";
		// replyID
		inputs[2].streamAsInputUGen(key, stream, decomp);
		stream << ")";
	}
}

+ Dpoll {
	streamCode { |key, stream, decomp|
		var numValues;
		this.streamNameAndRate(stream);
		stream << "(";
		inputs[0].streamAsInputUGen(key, stream, decomp);  // input
		stream << ", \"";
		inputs[3].do { |i|
			if(inputs[4+i] == 34) { stream << "\\" };  // escape single quote
			stream << inputs[4+i].asAscii;
		};
		stream << "\", ";
		inputs[2].streamAsInputUGen(key, stream, decomp);  // run
		stream << ", ";
		inputs[1].streamAsInputUGen(key, stream, decomp);  // trigid
		stream << ")";
	}
}

// freqscale, freqoffset, freq, amp, phase...
// Klang.ar(specificationsArrayRef, freqscale, freqoffset)
+ Klang {
	streamCode { |key, stream, decomp|
		this.streamNameAndRate(stream);
		stream << "(`[";
		inputs[2, 5..].streamAsInputUGen(key, stream, decomp);
		stream << ", ";
		inputs[3, 6..].streamAsInputUGen(key, stream, decomp);
		stream << ", ";
		inputs[4, 7..].streamAsInputUGen(key, stream, decomp);
		stream << "], ";
		inputs[0].streamAsInputUGen(key, stream, decomp);  // freqscale
		stream << ", ";
		inputs[1].streamAsInputUGen(key, stream, decomp);  // freqoffset
		stream << ")";
	}
}

// input, freqscale, freqoffset, decayscale
// Klank.ar(specificationsArrayRef, input, freqscale, freqoffset, decayscale)
+ Klank {
	streamCode { |key, stream, decomp|
		this.streamNameAndRate(stream);
		stream << "(`[";
		inputs[4, 7..].streamAsInputUGen(key, stream, decomp);
		stream << ", ";
		inputs[5, 8..].streamAsInputUGen(key, stream, decomp);
		stream << ", ";
		inputs[6, 9..].streamAsInputUGen(key, stream, decomp);
		stream << "]";
		4.do { |i|
			stream << ", ";
			inputs[i].streamAsInputUGen(key, stream, decomp);
		};
		stream << ")";
	}
}

+ DUGen {
	streamNameAndRate { |stream|
		stream << this.class.name
	}
}

// most do 'repeats, list...'
+ ListDUGen {
	streamCode { |key, stream, decomp|
		this.streamNameAndRate(stream);
		stream << "(";
		inputs[1..].streamAsInputUGen(key, stream, decomp);
		stream << ", ";
		inputs[0].streamAsInputUGen(key, stream, decomp);
		stream << ")";
	}
}

+ Dwrand {
	streamCode { |key, stream, decomp|
		this.streamNameAndRate(stream);
		stream << "(";
		// inputs[1] is guaranteed to be an integer
		inputs[2 + inputs[1] .. ].streamAsInputUGen(key, stream, decomp);
		stream << ", ";
		inputs[2 .. 2 + inputs[1] - 1].streamAsInputUGen(key, stream, decomp);
		stream << ", ";
		inputs[0].streamAsInputUGen(key, stream, decomp);
		// inputs[1] is size, which is not specified in Dwrand.new
		stream << ")";
	}
}

// units with numChannels arguments
// + InBus {
// 	streamCode { |key, stream, decomp|
//
// 	}
// }

// pseudoUGen
// + SplayAz {
// }

// pseudoUGen
// + Tap {
// }

+ PanAz {
	streamCode { |key, stream, decomp|
		this.streamNameAndRate(stream);
		stream << "(" << channels.size;
		inputs.do { |item, i|
			stream << ", ";
			item.streamAsInputUGen(key, stream, decomp);
		};
		stream << ")";
	}
}

+ DiskIn {
	streamCode { |key, stream, decomp|
		this.streamNameAndRate(stream);
		stream << "(" << channels.size;
		inputs.do { |item, i|
			stream << ", ";
			item.streamAsInputUGen(key, stream, decomp);
		};
		stream << ")";
	}
}

+ Warp1 {
	streamCode { |key, stream, decomp|
		this.streamNameAndRate(stream);
		stream << "(" << channels.size;
		inputs.do { |item, i|
			stream << ", ";
			item.streamAsInputUGen(key, stream, decomp);
		};
		stream << ")";
	}
}

// AbstractIn implementation takes precedence -- OutputProxies have already been resolved
// + InFeedback {
// }

// + LocalIn {
// }

// + In {
// 	streamCode { |key, stream, decomp|
// 		this.streamNameAndRate(stream);
// 		stream << "(";
// 		inputs.unbubble.streamAsInputUGen(key, stream, decomp);
// 		stream << ", ";
// 		stream << channels.size;
// 		stream << ")";
// 	}
// }

+ BufRd {
	streamCode { |key, stream, decomp|
		this.streamNameAndRate(stream);
		stream << "(" << channels.size;
		inputs.do { |item, i|
			stream << ", ";
			item.streamAsInputUGen(key, stream, decomp);
		};
		stream << ")";
	}
}

+ GrainBuf {
	streamCode { |key, stream, decomp|
		this.streamNameAndRate(stream);
		stream << "(" << channels.size;
		inputs.do { |item, i|
			stream << ", ";
			item.streamAsInputUGen(key, stream, decomp);
		};
		stream << ")";
	}
}

+ PlayBuf {
	streamCode { |key, stream, decomp|
		this.streamNameAndRate(stream);
		stream << "(" << channels.size;
		inputs.do { |item, i|
			stream << ", ";
			item.streamAsInputUGen(key, stream, decomp);
		};
		stream << ")";
	}
}

+ GrainFM {
	streamCode { |key, stream, decomp|
		this.streamNameAndRate(stream);
		stream << "(" << channels.size;
		inputs.do { |item, i|
			stream << ", ";
			item.streamAsInputUGen(key, stream, decomp);
		};
		stream << ")";
	}
}

+ DecodeB2 {
	streamCode { |key, stream, decomp|
		this.streamNameAndRate(stream);
		stream << "(" << channels.size;
		inputs.do { |item, i|
			stream << ", ";
			item.streamAsInputUGen(key, stream, decomp);
		};
		stream << ")";
	}
}

+ GrainSin {
	streamCode { |key, stream, decomp|
		this.streamNameAndRate(stream);
		stream << "(" << channels.size;
		inputs.do { |item, i|
			stream << ", ";
			item.streamAsInputUGen(key, stream, decomp);
		};
		stream << ")";
	}
}

+ GrainIn {
	streamCode { |key, stream, decomp|
		this.streamNameAndRate(stream);
		stream << "(" << channels.size;
		inputs.do { |item, i|
			stream << ", ";
			item.streamAsInputUGen(key, stream, decomp);
		};
		stream << ")";
	}
}

+ TGrains {
	streamCode { |key, stream, decomp|
		this.streamNameAndRate(stream);
		stream << "(" << channels.size;
		inputs.do { |item, i|
			stream << ", ";
			item.streamAsInputUGen(key, stream, decomp);
		};
		stream << ")";
	}
}

+ VDiskIn {
	streamCode { |key, stream, decomp|
		this.streamNameAndRate(stream);
		stream << "(" << channels.size;
		inputs.do { |item, i|
			stream << ", ";
			item.streamAsInputUGen(key, stream, decomp);
		};
		stream << ")";
	}
}

// FFT: no rate indicators
+ PV_ChainUGen {
	streamNameAndRate { |stream|
		stream << this.class.name
	}
}

+ IFFT {
	streamNameAndRate { |stream|
		stream << this.class.name
	}
}

+ MaxLocalBufs {
	// streamCode { |key, stream|
	// 	stream << "0 /* MaxLocalBufs should never appear directly */";
	// }
	decompilerCanOptimizeOut { ^true }
}

+ LocalBuf {
	streamCode { |key, stream, decomp|
		stream << this.class.name << "(";
		inputs[1].streamAsInputUGen(key, stream, decomp);
		stream << ", ";
		inputs[0].streamAsInputUGen(key, stream, decomp);
		stream << ")";
	}
}


// skip muladd in the middle
+ Line {
	streamCode { |stream, decomp|
		this.streamNameAndRate(stream);
		stream << "(";
		3.do { |i|
			if(i > 0) { stream << ", " };
			inputs[i].streamAsInputUGen(stream, decomp);
		};
		stream << ", doneAction: ";
		inputs[3].streamAsInputUGen(stream, decomp);
		stream << ")";
	}
}

+ XLine {
	streamCode { |stream, decomp|
		this.streamNameAndRate(stream);
		stream << "(";
		3.do { |i|
			if(i > 0) { stream << ", " };
			inputs[i].streamAsInputUGen(stream, decomp);
		};
		stream << ", doneAction: ";
		inputs[3].streamAsInputUGen(stream, decomp);
		stream << ")";
	}
}

