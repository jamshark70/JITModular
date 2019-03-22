// work around a bug in ProxyMixer
// where edit buttons don't ensure the right environment

+ ProxyMixer {
	setEdButs { |isSmall = false|
		var saveEnvir;
		(arGuis ++ krGuis).do { |pxgui|
			pxgui.edBut.states_([
				["ed", skin.fontColor, skin.background],
				["ed", skin.fontColor, skin.onColor]])

			.action_({ arg btn, mod;
				saveEnvir = currentEnvironment;
				if([EnvironmentRedirect, Environment].any { |class| object.isKindOf(class) }) {
					currentEnvironment = object;
				};
				protect {
					if (mod.notNil and: { mod.isAlt }) {
						NdefGui(pxgui.object);
					} {
						this.switchSize(2, isSmall);
						editGui.object_(pxgui.object);
						arGuis.do { |gui| gui.edBut.value_(0) };
						krGuis.do { |gui| gui.edBut.value_(0) };
						btn.value_(1);
					};
				} {
					currentEnvironment = saveEnvir;
				};
			});
		};
	}
}
