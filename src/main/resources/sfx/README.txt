Sound samples in this folder (13 files, 16-bit mono 44.1 kHz PCM WAV).
All come from Kenney's audio packs, released under Creative Commons Zero (CC0 1.0, public domain):
  https://creativecommons.org/publicdomain/zero/1.0/
Credit is not required by CC0; we keep it anyway: "Sound effects: Kenney (www.kenney.nl), CC0 1.0".
Each file was decoded from the pack's OGG Vorbis file, high-passed at 30 Hz, trimmed (1 ms before the onset,
15 ms after the -50 dB tail), faded, and peak-normalised to -1 dBFS. The game sets the playing level itself.
Everything else you hear is synthesized in code (src/main/java/kr/ac/jbnu/se/tetris/audio/SoundBank.java).

file	used for	pack (page)	original file	original sha256
ui_move.wav	MENU_MOVE	Kenney "Interface Sounds" 1.0 (https://kenney.nl/assets/interface-sounds)	select_002.ogg	4cc6ac5341b9b2c86fef80c347de028e9ab9f2e037586ac7d9fa59a403a1c012
ui_select.wav	MENU_SELECT	Kenney "Interface Sounds" 1.0 (https://kenney.nl/assets/interface-sounds)	confirmation_001.ogg	063564703b6094d70718a3e787a55cc9141611e4ecd6b6637f8828f79b4a8c3a
ui_back.wav	MENU_BACK	Kenney "Interface Sounds" 1.0 (https://kenney.nl/assets/interface-sounds)	question_002.ogg	46056f10c70dda860e93bd27b66f4d33c0b7154eff28def06471b50028efa5b3
ui_deny.wav	DENY	Kenney "Interface Sounds" 1.0 (https://kenney.nl/assets/interface-sounds)	error_004.ogg	0b574cea597d96507e782ae9764f88482ce49f46e931e57054bf7150047f2d69
land.wav	LAND	Kenney "Impact Sounds" 1.0 (https://kenney.nl/assets/impact-sounds)	impactGeneric_light_003.ogg	0e8896706130f6bc88cdf1f0157aaacdbe88dce86cf0330f9136e44eb1209e81
lock_1.wav	LOCK	Kenney "Impact Sounds" 1.0 (https://kenney.nl/assets/impact-sounds)	impactGeneric_light_000.ogg	f0e982611e97512fee5f777986b67e8b435434b601f94992ec044f7e89fb5acb
lock_2.wav	LOCK	Kenney "Impact Sounds" 1.0 (https://kenney.nl/assets/impact-sounds)	impactGeneric_light_002.ogg	d0bf60905b59ff630ffe01eb0edeeacd2504eaa8fb6523aeef7d2458b78264d3
hard_drop_1.wav	HARD_DROP	Kenney "Impact Sounds" 1.0 (https://kenney.nl/assets/impact-sounds)	impactWood_medium_000.ogg	1723a9fd25103ed41af06054814e84e65f4a772bac86ab2dc927da93d9592ff5
hard_drop_2.wav	HARD_DROP	Kenney "Impact Sounds" 1.0 (https://kenney.nl/assets/impact-sounds)	impactWood_medium_002.ogg	3f3f1917428b828f733a963167833c707bf88296ff158e1559c57c5cb813d799
ko_crunch.wav	KO (layer)	Kenney "Sci-fi Sounds" 1.0 (https://kenney.nl/assets/sci-fi-sounds)	explosionCrunch_000.ogg	4b597d658d0ae101f0a030fbeea5fc3a4292ab85f017470a8254a8e7959cbd69
sand_land_1.wav	SAND_LAND	Kenney "Impact Sounds" 1.0 (https://kenney.nl/assets/impact-sounds)	footstep_snow_003.ogg	68272e3f1cabcabd02d328fd344454d7c04cb58b5a86972fcdd858f845e3d007
sand_land_2.wav	SAND_LAND	Kenney "Impact Sounds" 1.0 (https://kenney.nl/assets/impact-sounds)	footstep_snow_000.ogg	c05fa4a9dc9a7aabc04996466fb589537e49af76de9998d8f9e6270ec92dcef7
sand_land_3.wav	SAND_LAND	Kenney "Impact Sounds" 1.0 (https://kenney.nl/assets/impact-sounds)	footstep_snow_002.ogg	db52bafdc66304f3b999f2c29227826c65993916e630ac2ffbca17d07b263178
