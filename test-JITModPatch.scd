p = JITModPatch.new;
p.save;
p.clear;

p = JITModPatch.load;
p.clear;

p = JITModPatch.load;

s.boot;
p = JITModPatch(s, \test);

~out = { \in.ar(0!2) }; ~out.play(vol: 0.2);
~freq = { LFDNoise3.kr(14).exprange(55, 1200) };
~osc = { VarSaw.ar(~freq.kr, 0, Lag.kr(LFDNoise0.kr(8.7).range(0.01, 0.5), 0.05)) };
~osc <>> ~out;

~out = { \in.ar(0!2) }; ~out.play(vol: 0.2);
~freq = { LFDNoise3.kr(14).exprange(55, 1200) };
~osc = { |speed = 8.7|
	VarSaw.ar(~freq.kr, 0, Lag.kr(LFDNoise0.kr(speed).range(0.01, 0.5), 0.05))
};
~osc.addSpec(\speed, [1, 15, \exp]);
~osc <>> ~out;

p.save(thisProcess.nowExecutingPath.dirname +/+ "testpatch.jitmod");
p.save(thisProcess.nowExecutingPath.dirname +/+ "testpatch2.jitmod");
p.save(thisProcess.nowExecutingPath.dirname +/+ "testpatch3.jitmod");

p.clear;

p.load(thisProcess.nowExecutingPath.dirname +/+ "testpatch.jitmod");

p = JITModPatch.load(thisProcess.nowExecutingPath.dirname +/+ "testpatch.jitmod");
p = JITModPatch.load(thisProcess.nowExecutingPath.dirname +/+ "testpatch2.jitmod");
p = JITModPatch.load(thisProcess.nowExecutingPath.dirname +/+ "testpatch3.jitmod");

p.proxyspace[\out].stop

p.proxyspace.use { p.proxyspace.storeOn2(Post) };
Halo.at(p.proxyspace[\osc])

m = JITModMIDI(p.proxyspace);
m.addCtl(1, \freq, \freq);
m.slotAt(\midiFuncs).at(\name1).chan

m.removeCtl(1, \freq);

m.addCtl(1, \speed, [1, 15, \exp]);

MIDIIn.doControlAction(MIDIClient.sources[0].uid, 0, 1, 100);

m.removeCtl(1, \speed);

MIDIIn.doControlAction(MIDIClient.sources[0].uid, 0, 1, 60);

m.channel = 0;

p.addCtl(1, \speed);

p.midi.postcs
p.midi.inspect

// halo checking
Halo.at(p.proxyspace[\osc])[\spec]

// huh. string problem?
d = Document.allDocuments.detect { |doc| doc.title.beginsWith("JITModPatch:") };
d.string
d.string(0, 1 << 24);
d.selectRange(0, 1 << 24);
d.string

p.doc.string




// try with buffers
s.boot; p = JITModPatch(s, \bufs);
p.readBuf(\a11, Platform.resourceDir +/+ "sounds/a11wlk01.wav");

p.buffers.buffers

p[\out].nodeMap

p.readBuf(\a11, Platform.resourceDir +/+ "sounds/a11wlk01.wav");

~out = { \in.ar(0!2) }; ~out.play(vol: 0.2);
~src = { |a11| PlayBuf.ar(1, a11, loop: 1) };
~src <>> ~out;

p.clear;
p.buffers.buffers

p.save;

p.buffers.asRef(\a11).asControlInput

p = JITModPatch.load;

d = DebugNetAddr("127.0.0.1", 57110);
e = s.addr;

s.addr = d;
s.addr = e;

currentEnvironment
Document.current.envir

// hum, something wrong with Document environments, closing maybe?
d = Document("test", "currentEnvironment;\nd.close", (a: 2));
Document.current.envir

s.boot; p = JITModPatch(s, \test3);
p.save;


// buffers in patterns
a = NodeProxy(s, \audio, 2);

a.source = Pbind(\degree, Pwhite(-14, 14, inf), \dur, 1, \test, Pfunc { |ev| ev[\abc].debug("abc in proto"); 0 }).trace;

a.nodeMap

a.source = Pbind(\degree, Pwhite(-14, 14, inf), \dur, 1, \play, { ~finish.value; currentEnvironment.debug("play") });

a.set(\abc, 1);  // picked up by ~finish!

a.clear;

// ok, try it for real -- a11 does get sent -- simple patterns are OK
// but a11 is not available for calculations
p = JITModPatch(s, \buf2);
p.save;
p.clear;


Pattern.findRespondingMethodFor(\makeProxyControl)



// buffer read failure
p = JITModPatch.new;

p.readBuf(\fail1, Platform.resourceDir +/+ "sounds/nope.wav");
p.buffers.buffers
s.bufferAllocator.debug

p.readBuf(\a11, Platform.resourceDir +/+ "sounds/a11wlk01.wav");

// try to replace with failed
p.readBuf(\a11, Platform.resourceDir +/+ "sounds/nope.wav");

p.clear;





p = JITModPatch.new;

~out = { \in.ar(0!2) }; ~out.play(vol: 0.2);
~freq = 200;
~osc = { VarSaw.ar(~freq.kr, 0, 0.15) };
~osc <>> ~out;
p.addCtl(1, \freq, \freq);

p = JITModPatch.load;
