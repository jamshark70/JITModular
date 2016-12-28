+ ProxySpace {
	setEvent { |event|
		var out = (type: \psSet, gt: 1, t_trig: 1, proto: (proxyspace: this));
		if(event.isKindOf(Dictionary)) { out.putAll(event) };
		^out
	}
}
