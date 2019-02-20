s.boot;
p = JITModPatch(s, \test);

~out = { \in.ar(0!2) }; ~out.play(vol: 0.2);
~freq = { LFDNoise3.kr(14).exprange(55, 1200) };
~osc = { VarSaw.ar(~freq.kr, 0, Lag.kr(LFDNoise0.kr(8.7).range(0.01, 0.5), 0.05)) };
~osc <>> ~out;

p.save(thisProcess.nowExecutingPath.dirname +/+ "testpatch.jitmod");

p.clear;

p.load(thisProcess.nowExecutingPath.dirname +/+ "testpatch.jitmod");

p = JITModPatch.load(thisProcess.nowExecutingPath.dirname +/+ "testpatch.jitmod");
