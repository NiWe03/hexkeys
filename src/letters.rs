//! Written by tools/letters.py from anyascii's transliteration: every
//! lower-case letter the keys do not have, with the plain letters it is
//! written with, sorted for binary search.

pub(crate) const LETTERS: &[(char, &str)] = &[
    ('\u{00df}', "ss"), // ß LATIN SMALL LETTER SHARP S
    ('\u{00e0}', "a"), // à LATIN SMALL LETTER A WITH GRAVE
    ('\u{00e1}', "a"), // á LATIN SMALL LETTER A WITH ACUTE
    ('\u{00e2}', "a"), // â LATIN SMALL LETTER A WITH CIRCUMFLEX
    ('\u{00e3}', "a"), // ã LATIN SMALL LETTER A WITH TILDE
    ('\u{00e4}', "a"), // ä LATIN SMALL LETTER A WITH DIAERESIS
    ('\u{00e5}', "a"), // å LATIN SMALL LETTER A WITH RING ABOVE
    ('\u{00e6}', "ae"), // æ LATIN SMALL LETTER AE
    ('\u{00e7}', "c"), // ç LATIN SMALL LETTER C WITH CEDILLA
    ('\u{00e8}', "e"), // è LATIN SMALL LETTER E WITH GRAVE
    ('\u{00e9}', "e"), // é LATIN SMALL LETTER E WITH ACUTE
    ('\u{00ea}', "e"), // ê LATIN SMALL LETTER E WITH CIRCUMFLEX
    ('\u{00eb}', "e"), // ë LATIN SMALL LETTER E WITH DIAERESIS
    ('\u{00ec}', "i"), // ì LATIN SMALL LETTER I WITH GRAVE
    ('\u{00ed}', "i"), // í LATIN SMALL LETTER I WITH ACUTE
    ('\u{00ee}', "i"), // î LATIN SMALL LETTER I WITH CIRCUMFLEX
    ('\u{00ef}', "i"), // ï LATIN SMALL LETTER I WITH DIAERESIS
    ('\u{00f0}', "d"), // ð LATIN SMALL LETTER ETH
    ('\u{00f1}', "n"), // ñ LATIN SMALL LETTER N WITH TILDE
    ('\u{00f2}', "o"), // ò LATIN SMALL LETTER O WITH GRAVE
    ('\u{00f3}', "o"), // ó LATIN SMALL LETTER O WITH ACUTE
    ('\u{00f4}', "o"), // ô LATIN SMALL LETTER O WITH CIRCUMFLEX
    ('\u{00f5}', "o"), // õ LATIN SMALL LETTER O WITH TILDE
    ('\u{00f6}', "o"), // ö LATIN SMALL LETTER O WITH DIAERESIS
    ('\u{00f8}', "o"), // ø LATIN SMALL LETTER O WITH STROKE
    ('\u{00f9}', "u"), // ù LATIN SMALL LETTER U WITH GRAVE
    ('\u{00fa}', "u"), // ú LATIN SMALL LETTER U WITH ACUTE
    ('\u{00fb}', "u"), // û LATIN SMALL LETTER U WITH CIRCUMFLEX
    ('\u{00fc}', "u"), // ü LATIN SMALL LETTER U WITH DIAERESIS
    ('\u{00fd}', "y"), // ý LATIN SMALL LETTER Y WITH ACUTE
    ('\u{00fe}', "th"), // þ LATIN SMALL LETTER THORN
    ('\u{00ff}', "y"), // ÿ LATIN SMALL LETTER Y WITH DIAERESIS
    ('\u{0101}', "a"), // ā LATIN SMALL LETTER A WITH MACRON
    ('\u{0103}', "a"), // ă LATIN SMALL LETTER A WITH BREVE
    ('\u{0105}', "a"), // ą LATIN SMALL LETTER A WITH OGONEK
    ('\u{0107}', "c"), // ć LATIN SMALL LETTER C WITH ACUTE
    ('\u{0109}', "c"), // ĉ LATIN SMALL LETTER C WITH CIRCUMFLEX
    ('\u{010b}', "c"), // ċ LATIN SMALL LETTER C WITH DOT ABOVE
    ('\u{010d}', "c"), // č LATIN SMALL LETTER C WITH CARON
    ('\u{010f}', "d"), // ď LATIN SMALL LETTER D WITH CARON
    ('\u{0111}', "d"), // đ LATIN SMALL LETTER D WITH STROKE
    ('\u{0113}', "e"), // ē LATIN SMALL LETTER E WITH MACRON
    ('\u{0115}', "e"), // ĕ LATIN SMALL LETTER E WITH BREVE
    ('\u{0117}', "e"), // ė LATIN SMALL LETTER E WITH DOT ABOVE
    ('\u{0119}', "e"), // ę LATIN SMALL LETTER E WITH OGONEK
    ('\u{011b}', "e"), // ě LATIN SMALL LETTER E WITH CARON
    ('\u{011d}', "g"), // ĝ LATIN SMALL LETTER G WITH CIRCUMFLEX
    ('\u{011f}', "g"), // ğ LATIN SMALL LETTER G WITH BREVE
    ('\u{0121}', "g"), // ġ LATIN SMALL LETTER G WITH DOT ABOVE
    ('\u{0123}', "g"), // ģ LATIN SMALL LETTER G WITH CEDILLA
    ('\u{0125}', "h"), // ĥ LATIN SMALL LETTER H WITH CIRCUMFLEX
    ('\u{0127}', "h"), // ħ LATIN SMALL LETTER H WITH STROKE
    ('\u{0129}', "i"), // ĩ LATIN SMALL LETTER I WITH TILDE
    ('\u{012b}', "i"), // ī LATIN SMALL LETTER I WITH MACRON
    ('\u{012d}', "i"), // ĭ LATIN SMALL LETTER I WITH BREVE
    ('\u{012f}', "i"), // į LATIN SMALL LETTER I WITH OGONEK
    ('\u{0131}', "i"), // ı LATIN SMALL LETTER DOTLESS I
    ('\u{0133}', "ij"), // ĳ LATIN SMALL LIGATURE IJ
    ('\u{0135}', "j"), // ĵ LATIN SMALL LETTER J WITH CIRCUMFLEX
    ('\u{0137}', "k"), // ķ LATIN SMALL LETTER K WITH CEDILLA
    ('\u{0138}', "q"), // ĸ LATIN SMALL LETTER KRA
    ('\u{013a}', "l"), // ĺ LATIN SMALL LETTER L WITH ACUTE
    ('\u{013c}', "l"), // ļ LATIN SMALL LETTER L WITH CEDILLA
    ('\u{013e}', "l"), // ľ LATIN SMALL LETTER L WITH CARON
    ('\u{0140}', "l"), // ŀ LATIN SMALL LETTER L WITH MIDDLE DOT
    ('\u{0142}', "l"), // ł LATIN SMALL LETTER L WITH STROKE
    ('\u{0144}', "n"), // ń LATIN SMALL LETTER N WITH ACUTE
    ('\u{0146}', "n"), // ņ LATIN SMALL LETTER N WITH CEDILLA
    ('\u{0148}', "n"), // ň LATIN SMALL LETTER N WITH CARON
    ('\u{014b}', "ng"), // ŋ LATIN SMALL LETTER ENG
    ('\u{014d}', "o"), // ō LATIN SMALL LETTER O WITH MACRON
    ('\u{014f}', "o"), // ŏ LATIN SMALL LETTER O WITH BREVE
    ('\u{0151}', "o"), // ő LATIN SMALL LETTER O WITH DOUBLE ACUTE
    ('\u{0153}', "oe"), // œ LATIN SMALL LIGATURE OE
    ('\u{0155}', "r"), // ŕ LATIN SMALL LETTER R WITH ACUTE
    ('\u{0157}', "r"), // ŗ LATIN SMALL LETTER R WITH CEDILLA
    ('\u{0159}', "r"), // ř LATIN SMALL LETTER R WITH CARON
    ('\u{015b}', "s"), // ś LATIN SMALL LETTER S WITH ACUTE
    ('\u{015d}', "s"), // ŝ LATIN SMALL LETTER S WITH CIRCUMFLEX
    ('\u{015f}', "s"), // ş LATIN SMALL LETTER S WITH CEDILLA
    ('\u{0161}', "s"), // š LATIN SMALL LETTER S WITH CARON
    ('\u{0163}', "t"), // ţ LATIN SMALL LETTER T WITH CEDILLA
    ('\u{0165}', "t"), // ť LATIN SMALL LETTER T WITH CARON
    ('\u{0167}', "t"), // ŧ LATIN SMALL LETTER T WITH STROKE
    ('\u{0169}', "u"), // ũ LATIN SMALL LETTER U WITH TILDE
    ('\u{016b}', "u"), // ū LATIN SMALL LETTER U WITH MACRON
    ('\u{016d}', "u"), // ŭ LATIN SMALL LETTER U WITH BREVE
    ('\u{016f}', "u"), // ů LATIN SMALL LETTER U WITH RING ABOVE
    ('\u{0171}', "u"), // ű LATIN SMALL LETTER U WITH DOUBLE ACUTE
    ('\u{0173}', "u"), // ų LATIN SMALL LETTER U WITH OGONEK
    ('\u{0175}', "w"), // ŵ LATIN SMALL LETTER W WITH CIRCUMFLEX
    ('\u{0177}', "y"), // ŷ LATIN SMALL LETTER Y WITH CIRCUMFLEX
    ('\u{017a}', "z"), // ź LATIN SMALL LETTER Z WITH ACUTE
    ('\u{017c}', "z"), // ż LATIN SMALL LETTER Z WITH DOT ABOVE
    ('\u{017e}', "z"), // ž LATIN SMALL LETTER Z WITH CARON
    ('\u{017f}', "s"), // ſ LATIN SMALL LETTER LONG S
    ('\u{0180}', "b"), // ƀ LATIN SMALL LETTER B WITH STROKE
    ('\u{0183}', "b"), // ƃ LATIN SMALL LETTER B WITH TOPBAR
    ('\u{0188}', "c"), // ƈ LATIN SMALL LETTER C WITH HOOK
    ('\u{018c}', "d"), // ƌ LATIN SMALL LETTER D WITH TOPBAR
    ('\u{018d}', "z"), // ƍ LATIN SMALL LETTER TURNED DELTA
    ('\u{0192}', "f"), // ƒ LATIN SMALL LETTER F WITH HOOK
    ('\u{0195}', "hw"), // ƕ LATIN SMALL LETTER HV
    ('\u{0199}', "k"), // ƙ LATIN SMALL LETTER K WITH HOOK
    ('\u{019a}', "l"), // ƚ LATIN SMALL LETTER L WITH BAR
    ('\u{019b}', "l"), // ƛ LATIN SMALL LETTER LAMBDA WITH STROKE
    ('\u{019e}', "n"), // ƞ LATIN SMALL LETTER N WITH LONG RIGHT LEG
    ('\u{01a1}', "o"), // ơ LATIN SMALL LETTER O WITH HORN
    ('\u{01a3}', "gh"), // ƣ LATIN SMALL LETTER OI
    ('\u{01a5}', "p"), // ƥ LATIN SMALL LETTER P WITH HOOK
    ('\u{01aa}', "sh"), // ƪ LATIN LETTER REVERSED ESH LOOP
    ('\u{01ab}', "t"), // ƫ LATIN SMALL LETTER T WITH PALATAL HOOK
    ('\u{01ad}', "t"), // ƭ LATIN SMALL LETTER T WITH HOOK
    ('\u{01b0}', "u"), // ư LATIN SMALL LETTER U WITH HORN
    ('\u{01b4}', "y"), // ƴ LATIN SMALL LETTER Y WITH HOOK
    ('\u{01b6}', "z"), // ƶ LATIN SMALL LETTER Z WITH STROKE
    ('\u{01ba}', "zh"), // ƺ LATIN SMALL LETTER EZH WITH TAIL
    ('\u{01bb}', "dz"), // ƻ LATIN LETTER TWO WITH STROKE
    ('\u{01be}', "ts"), // ƾ LATIN LETTER INVERTED GLOTTAL STOP WITH STROKE
    ('\u{01bf}', "w"), // ƿ LATIN LETTER WYNN
    ('\u{01c0}', "c"), // ǀ LATIN LETTER DENTAL CLICK
    ('\u{01c1}', "x"), // ǁ LATIN LETTER LATERAL CLICK
    ('\u{01c2}', "qc"), // ǂ LATIN LETTER ALVEOLAR CLICK
    ('\u{01c3}', "q"), // ǃ LATIN LETTER RETROFLEX CLICK
    ('\u{01c6}', "dz"), // ǆ LATIN SMALL LETTER DZ WITH CARON
    ('\u{01c9}', "lj"), // ǉ LATIN SMALL LETTER LJ
    ('\u{01cc}', "nj"), // ǌ LATIN SMALL LETTER NJ
    ('\u{01ce}', "a"), // ǎ LATIN SMALL LETTER A WITH CARON
    ('\u{01d0}', "i"), // ǐ LATIN SMALL LETTER I WITH CARON
    ('\u{01d2}', "o"), // ǒ LATIN SMALL LETTER O WITH CARON
    ('\u{01d4}', "u"), // ǔ LATIN SMALL LETTER U WITH CARON
    ('\u{01d6}', "u"), // ǖ LATIN SMALL LETTER U WITH DIAERESIS AND MACRON
    ('\u{01d8}', "u"), // ǘ LATIN SMALL LETTER U WITH DIAERESIS AND ACUTE
    ('\u{01da}', "u"), // ǚ LATIN SMALL LETTER U WITH DIAERESIS AND CARON
    ('\u{01dc}', "u"), // ǜ LATIN SMALL LETTER U WITH DIAERESIS AND GRAVE
    ('\u{01dd}', "e"), // ǝ LATIN SMALL LETTER TURNED E
    ('\u{01df}', "a"), // ǟ LATIN SMALL LETTER A WITH DIAERESIS AND MACRON
    ('\u{01e1}', "a"), // ǡ LATIN SMALL LETTER A WITH DOT ABOVE AND MACRON
    ('\u{01e3}', "ae"), // ǣ LATIN SMALL LETTER AE WITH MACRON
    ('\u{01e5}', "g"), // ǥ LATIN SMALL LETTER G WITH STROKE
    ('\u{01e7}', "g"), // ǧ LATIN SMALL LETTER G WITH CARON
    ('\u{01e9}', "k"), // ǩ LATIN SMALL LETTER K WITH CARON
    ('\u{01eb}', "o"), // ǫ LATIN SMALL LETTER O WITH OGONEK
    ('\u{01ed}', "o"), // ǭ LATIN SMALL LETTER O WITH OGONEK AND MACRON
    ('\u{01ef}', "zh"), // ǯ LATIN SMALL LETTER EZH WITH CARON
    ('\u{01f0}', "j"), // ǰ LATIN SMALL LETTER J WITH CARON
    ('\u{01f3}', "dz"), // ǳ LATIN SMALL LETTER DZ
    ('\u{01f5}', "g"), // ǵ LATIN SMALL LETTER G WITH ACUTE
    ('\u{01f9}', "n"), // ǹ LATIN SMALL LETTER N WITH GRAVE
    ('\u{01fb}', "a"), // ǻ LATIN SMALL LETTER A WITH RING ABOVE AND ACUTE
    ('\u{01fd}', "ae"), // ǽ LATIN SMALL LETTER AE WITH ACUTE
    ('\u{01ff}', "o"), // ǿ LATIN SMALL LETTER O WITH STROKE AND ACUTE
    ('\u{0201}', "a"), // ȁ LATIN SMALL LETTER A WITH DOUBLE GRAVE
    ('\u{0203}', "a"), // ȃ LATIN SMALL LETTER A WITH INVERTED BREVE
    ('\u{0205}', "e"), // ȅ LATIN SMALL LETTER E WITH DOUBLE GRAVE
    ('\u{0207}', "e"), // ȇ LATIN SMALL LETTER E WITH INVERTED BREVE
    ('\u{0209}', "i"), // ȉ LATIN SMALL LETTER I WITH DOUBLE GRAVE
    ('\u{020b}', "i"), // ȋ LATIN SMALL LETTER I WITH INVERTED BREVE
    ('\u{020d}', "o"), // ȍ LATIN SMALL LETTER O WITH DOUBLE GRAVE
    ('\u{020f}', "o"), // ȏ LATIN SMALL LETTER O WITH INVERTED BREVE
    ('\u{0211}', "r"), // ȑ LATIN SMALL LETTER R WITH DOUBLE GRAVE
    ('\u{0213}', "r"), // ȓ LATIN SMALL LETTER R WITH INVERTED BREVE
    ('\u{0215}', "u"), // ȕ LATIN SMALL LETTER U WITH DOUBLE GRAVE
    ('\u{0217}', "u"), // ȗ LATIN SMALL LETTER U WITH INVERTED BREVE
    ('\u{0219}', "s"), // ș LATIN SMALL LETTER S WITH COMMA BELOW
    ('\u{021b}', "t"), // ț LATIN SMALL LETTER T WITH COMMA BELOW
    ('\u{021d}', "y"), // ȝ LATIN SMALL LETTER YOGH
    ('\u{021f}', "h"), // ȟ LATIN SMALL LETTER H WITH CARON
    ('\u{0221}', "d"), // ȡ LATIN SMALL LETTER D WITH CURL
    ('\u{0223}', "ou"), // ȣ LATIN SMALL LETTER OU
    ('\u{0225}', "z"), // ȥ LATIN SMALL LETTER Z WITH HOOK
    ('\u{0227}', "a"), // ȧ LATIN SMALL LETTER A WITH DOT ABOVE
    ('\u{0229}', "e"), // ȩ LATIN SMALL LETTER E WITH CEDILLA
    ('\u{022b}', "o"), // ȫ LATIN SMALL LETTER O WITH DIAERESIS AND MACRON
    ('\u{022d}', "o"), // ȭ LATIN SMALL LETTER O WITH TILDE AND MACRON
    ('\u{022f}', "o"), // ȯ LATIN SMALL LETTER O WITH DOT ABOVE
    ('\u{0231}', "o"), // ȱ LATIN SMALL LETTER O WITH DOT ABOVE AND MACRON
    ('\u{0233}', "y"), // ȳ LATIN SMALL LETTER Y WITH MACRON
    ('\u{0234}', "l"), // ȴ LATIN SMALL LETTER L WITH CURL
    ('\u{0235}', "n"), // ȵ LATIN SMALL LETTER N WITH CURL
    ('\u{0236}', "t"), // ȶ LATIN SMALL LETTER T WITH CURL
    ('\u{0237}', "j"), // ȷ LATIN SMALL LETTER DOTLESS J
    ('\u{0238}', "db"), // ȸ LATIN SMALL LETTER DB DIGRAPH
    ('\u{0239}', "qp"), // ȹ LATIN SMALL LETTER QP DIGRAPH
    ('\u{023c}', "c"), // ȼ LATIN SMALL LETTER C WITH STROKE
    ('\u{023f}', "s"), // ȿ LATIN SMALL LETTER S WITH SWASH TAIL
    ('\u{0240}', "z"), // ɀ LATIN SMALL LETTER Z WITH SWASH TAIL
    ('\u{0247}', "e"), // ɇ LATIN SMALL LETTER E WITH STROKE
    ('\u{0249}', "j"), // ɉ LATIN SMALL LETTER J WITH STROKE
    ('\u{024b}', "q"), // ɋ LATIN SMALL LETTER Q WITH HOOK TAIL
    ('\u{024d}', "r"), // ɍ LATIN SMALL LETTER R WITH STROKE
    ('\u{024f}', "y"), // ɏ LATIN SMALL LETTER Y WITH STROKE
    ('\u{0250}', "a"), // ɐ LATIN SMALL LETTER TURNED A
    ('\u{0251}', "a"), // ɑ LATIN SMALL LETTER ALPHA
    ('\u{0252}', "a"), // ɒ LATIN SMALL LETTER TURNED ALPHA
    ('\u{0253}', "b"), // ɓ LATIN SMALL LETTER B WITH HOOK
    ('\u{0254}', "o"), // ɔ LATIN SMALL LETTER OPEN O
    ('\u{0255}', "c"), // ɕ LATIN SMALL LETTER C WITH CURL
    ('\u{0256}', "d"), // ɖ LATIN SMALL LETTER D WITH TAIL
    ('\u{0257}', "d"), // ɗ LATIN SMALL LETTER D WITH HOOK
    ('\u{0258}', "e"), // ɘ LATIN SMALL LETTER REVERSED E
    ('\u{0259}', "e"), // ə LATIN SMALL LETTER SCHWA
    ('\u{025a}', "er"), // ɚ LATIN SMALL LETTER SCHWA WITH HOOK
    ('\u{025b}', "e"), // ɛ LATIN SMALL LETTER OPEN E
    ('\u{025c}', "e"), // ɜ LATIN SMALL LETTER REVERSED OPEN E
    ('\u{025d}', "er"), // ɝ LATIN SMALL LETTER REVERSED OPEN E WITH HOOK
    ('\u{025e}', "o"), // ɞ LATIN SMALL LETTER CLOSED REVERSED OPEN E
    ('\u{025f}', "j"), // ɟ LATIN SMALL LETTER DOTLESS J WITH STROKE
    ('\u{0260}', "g"), // ɠ LATIN SMALL LETTER G WITH HOOK
    ('\u{0261}', "g"), // ɡ LATIN SMALL LETTER SCRIPT G
    ('\u{0262}', "g"), // ɢ LATIN LETTER SMALL CAPITAL G
    ('\u{0263}', "g"), // ɣ LATIN SMALL LETTER GAMMA
    ('\u{0264}', "u"), // ɤ LATIN SMALL LETTER RAMS HORN
    ('\u{0265}', "y"), // ɥ LATIN SMALL LETTER TURNED H
    ('\u{0266}', "h"), // ɦ LATIN SMALL LETTER H WITH HOOK
    ('\u{0267}', "h"), // ɧ LATIN SMALL LETTER HENG WITH HOOK
    ('\u{0268}', "i"), // ɨ LATIN SMALL LETTER I WITH STROKE
    ('\u{0269}', "i"), // ɩ LATIN SMALL LETTER IOTA
    ('\u{026a}', "i"), // ɪ LATIN LETTER SMALL CAPITAL I
    ('\u{026b}', "l"), // ɫ LATIN SMALL LETTER L WITH MIDDLE TILDE
    ('\u{026c}', "l"), // ɬ LATIN SMALL LETTER L WITH BELT
    ('\u{026d}', "l"), // ɭ LATIN SMALL LETTER L WITH RETROFLEX HOOK
    ('\u{026e}', "lzh"), // ɮ LATIN SMALL LETTER LEZH
    ('\u{026f}', "w"), // ɯ LATIN SMALL LETTER TURNED M
    ('\u{0270}', "w"), // ɰ LATIN SMALL LETTER TURNED M WITH LONG LEG
    ('\u{0271}', "m"), // ɱ LATIN SMALL LETTER M WITH HOOK
    ('\u{0272}', "n"), // ɲ LATIN SMALL LETTER N WITH LEFT HOOK
    ('\u{0273}', "n"), // ɳ LATIN SMALL LETTER N WITH RETROFLEX HOOK
    ('\u{0274}', "n"), // ɴ LATIN LETTER SMALL CAPITAL N
    ('\u{0275}', "o"), // ɵ LATIN SMALL LETTER BARRED O
    ('\u{0276}', "oe"), // ɶ LATIN LETTER SMALL CAPITAL OE
    ('\u{0277}', "u"), // ɷ LATIN SMALL LETTER CLOSED OMEGA
    ('\u{0278}', "f"), // ɸ LATIN SMALL LETTER PHI
    ('\u{0279}', "r"), // ɹ LATIN SMALL LETTER TURNED R
    ('\u{027a}', "r"), // ɺ LATIN SMALL LETTER TURNED R WITH LONG LEG
    ('\u{027b}', "r"), // ɻ LATIN SMALL LETTER TURNED R WITH HOOK
    ('\u{027c}', "r"), // ɼ LATIN SMALL LETTER R WITH LONG LEG
    ('\u{027d}', "r"), // ɽ LATIN SMALL LETTER R WITH TAIL
    ('\u{027e}', "r"), // ɾ LATIN SMALL LETTER R WITH FISHHOOK
    ('\u{027f}', "i"), // ɿ LATIN SMALL LETTER REVERSED R WITH FISHHOOK
    ('\u{0280}', "r"), // ʀ LATIN LETTER SMALL CAPITAL R
    ('\u{0281}', "r"), // ʁ LATIN LETTER SMALL CAPITAL INVERTED R
    ('\u{0282}', "s"), // ʂ LATIN SMALL LETTER S WITH HOOK
    ('\u{0283}', "sh"), // ʃ LATIN SMALL LETTER ESH
    ('\u{0284}', "j"), // ʄ LATIN SMALL LETTER DOTLESS J WITH STROKE AND HOOK
    ('\u{0285}', "i"), // ʅ LATIN SMALL LETTER SQUAT REVERSED ESH
    ('\u{0286}', "sh"), // ʆ LATIN SMALL LETTER ESH WITH CURL
    ('\u{0287}', "t"), // ʇ LATIN SMALL LETTER TURNED T
    ('\u{0288}', "t"), // ʈ LATIN SMALL LETTER T WITH RETROFLEX HOOK
    ('\u{0289}', "u"), // ʉ LATIN SMALL LETTER U BAR
    ('\u{028a}', "u"), // ʊ LATIN SMALL LETTER UPSILON
    ('\u{028b}', "v"), // ʋ LATIN SMALL LETTER V WITH HOOK
    ('\u{028c}', "a"), // ʌ LATIN SMALL LETTER TURNED V
    ('\u{028d}', "w"), // ʍ LATIN SMALL LETTER TURNED W
    ('\u{028e}', "y"), // ʎ LATIN SMALL LETTER TURNED Y
    ('\u{028f}', "y"), // ʏ LATIN LETTER SMALL CAPITAL Y
    ('\u{0290}', "z"), // ʐ LATIN SMALL LETTER Z WITH RETROFLEX HOOK
    ('\u{0291}', "z"), // ʑ LATIN SMALL LETTER Z WITH CURL
    ('\u{0292}', "zh"), // ʒ LATIN SMALL LETTER EZH
    ('\u{0293}', "zh"), // ʓ LATIN SMALL LETTER EZH WITH CURL
    ('\u{0296}', "x"), // ʖ LATIN LETTER INVERTED GLOTTAL STOP
    ('\u{0297}', "q"), // ʗ LATIN LETTER STRETCHED C
    ('\u{0298}', "pc"), // ʘ LATIN LETTER BILABIAL CLICK
    ('\u{0299}', "b"), // ʙ LATIN LETTER SMALL CAPITAL B
    ('\u{029a}', "oe"), // ʚ LATIN SMALL LETTER CLOSED OPEN E
    ('\u{029b}', "g"), // ʛ LATIN LETTER SMALL CAPITAL G WITH HOOK
    ('\u{029c}', "h"), // ʜ LATIN LETTER SMALL CAPITAL H
    ('\u{029d}', "j"), // ʝ LATIN SMALL LETTER J WITH CROSSED-TAIL
    ('\u{029e}', "k"), // ʞ LATIN SMALL LETTER TURNED K
    ('\u{029f}', "l"), // ʟ LATIN LETTER SMALL CAPITAL L
    ('\u{02a0}', "q"), // ʠ LATIN SMALL LETTER Q WITH HOOK
    ('\u{02a3}', "dz"), // ʣ LATIN SMALL LETTER DZ DIGRAPH
    ('\u{02a4}', "dzh"), // ʤ LATIN SMALL LETTER DEZH DIGRAPH
    ('\u{02a5}', "dz"), // ʥ LATIN SMALL LETTER DZ DIGRAPH WITH CURL
    ('\u{02a6}', "ts"), // ʦ LATIN SMALL LETTER TS DIGRAPH
    ('\u{02a7}', "tsh"), // ʧ LATIN SMALL LETTER TESH DIGRAPH
    ('\u{02a8}', "tc"), // ʨ LATIN SMALL LETTER TC DIGRAPH WITH CURL
    ('\u{02a9}', "fng"), // ʩ LATIN SMALL LETTER FENG DIGRAPH
    ('\u{02aa}', "ls"), // ʪ LATIN SMALL LETTER LS DIGRAPH
    ('\u{02ab}', "lz"), // ʫ LATIN SMALL LETTER LZ DIGRAPH
    ('\u{02ac}', "ww"), // ʬ LATIN LETTER BILABIAL PERCUSSIVE
    ('\u{02ae}', "y"), // ʮ LATIN SMALL LETTER TURNED H WITH FISHHOOK
    ('\u{02af}', "y"), // ʯ LATIN SMALL LETTER TURNED H WITH FISHHOOK AND TAIL
    ('\u{02b0}', "h"), // ʰ MODIFIER LETTER SMALL H
    ('\u{02b1}', "h"), // ʱ MODIFIER LETTER SMALL H WITH HOOK
    ('\u{02b2}', "j"), // ʲ MODIFIER LETTER SMALL J
    ('\u{02b3}', "r"), // ʳ MODIFIER LETTER SMALL R
    ('\u{02b4}', "r"), // ʴ MODIFIER LETTER SMALL TURNED R
    ('\u{02b5}', "r"), // ʵ MODIFIER LETTER SMALL TURNED R WITH HOOK
    ('\u{02b6}', "r"), // ʶ MODIFIER LETTER SMALL CAPITAL INVERTED R
    ('\u{02b7}', "w"), // ʷ MODIFIER LETTER SMALL W
    ('\u{02b8}', "y"), // ʸ MODIFIER LETTER SMALL Y
    ('\u{02e0}', "g"), // ˠ MODIFIER LETTER SMALL GAMMA
    ('\u{02e1}', "l"), // ˡ MODIFIER LETTER SMALL L
    ('\u{02e2}', "s"), // ˢ MODIFIER LETTER SMALL S
    ('\u{02e3}', "x"), // ˣ MODIFIER LETTER SMALL X
    ('\u{0371}', "h"), // ͱ GREEK SMALL LETTER HETA
    ('\u{0373}', "s"), // ͳ GREEK SMALL LETTER ARCHAIC SAMPI
    ('\u{0377}', "w"), // ͷ GREEK SMALL LETTER PAMPHYLIAN DIGAMMA
    ('\u{037a}', "i"), // ͺ GREEK YPOGEGRAMMENI
    ('\u{037b}', "s"), // ͻ GREEK SMALL REVERSED LUNATE SIGMA SYMBOL
    ('\u{0390}', "i"), // ΐ GREEK SMALL LETTER IOTA WITH DIALYTIKA AND TONOS
    ('\u{03ac}', "a"), // ά GREEK SMALL LETTER ALPHA WITH TONOS
    ('\u{03ad}', "e"), // έ GREEK SMALL LETTER EPSILON WITH TONOS
    ('\u{03ae}', "i"), // ή GREEK SMALL LETTER ETA WITH TONOS
    ('\u{03af}', "i"), // ί GREEK SMALL LETTER IOTA WITH TONOS
    ('\u{03b0}', "y"), // ΰ GREEK SMALL LETTER UPSILON WITH DIALYTIKA AND TONOS
    ('\u{03b1}', "a"), // α GREEK SMALL LETTER ALPHA
    ('\u{03b2}', "v"), // β GREEK SMALL LETTER BETA
    ('\u{03b3}', "g"), // γ GREEK SMALL LETTER GAMMA
    ('\u{03b4}', "d"), // δ GREEK SMALL LETTER DELTA
    ('\u{03b5}', "e"), // ε GREEK SMALL LETTER EPSILON
    ('\u{03b6}', "z"), // ζ GREEK SMALL LETTER ZETA
    ('\u{03b7}', "i"), // η GREEK SMALL LETTER ETA
    ('\u{03b8}', "th"), // θ GREEK SMALL LETTER THETA
    ('\u{03b9}', "i"), // ι GREEK SMALL LETTER IOTA
    ('\u{03ba}', "k"), // κ GREEK SMALL LETTER KAPPA
    ('\u{03bb}', "l"), // λ GREEK SMALL LETTER LAMDA
    ('\u{03bc}', "m"), // μ GREEK SMALL LETTER MU
    ('\u{03bd}', "n"), // ν GREEK SMALL LETTER NU
    ('\u{03be}', "x"), // ξ GREEK SMALL LETTER XI
    ('\u{03bf}', "o"), // ο GREEK SMALL LETTER OMICRON
    ('\u{03c0}', "p"), // π GREEK SMALL LETTER PI
    ('\u{03c1}', "r"), // ρ GREEK SMALL LETTER RHO
    ('\u{03c2}', "s"), // ς GREEK SMALL LETTER FINAL SIGMA
    ('\u{03c3}', "s"), // σ GREEK SMALL LETTER SIGMA
    ('\u{03c4}', "t"), // τ GREEK SMALL LETTER TAU
    ('\u{03c5}', "y"), // υ GREEK SMALL LETTER UPSILON
    ('\u{03c6}', "f"), // φ GREEK SMALL LETTER PHI
    ('\u{03c7}', "ch"), // χ GREEK SMALL LETTER CHI
    ('\u{03c8}', "ps"), // ψ GREEK SMALL LETTER PSI
    ('\u{03c9}', "o"), // ω GREEK SMALL LETTER OMEGA
    ('\u{03ca}', "i"), // ϊ GREEK SMALL LETTER IOTA WITH DIALYTIKA
    ('\u{03cb}', "y"), // ϋ GREEK SMALL LETTER UPSILON WITH DIALYTIKA
    ('\u{03cc}', "o"), // ό GREEK SMALL LETTER OMICRON WITH TONOS
    ('\u{03cd}', "y"), // ύ GREEK SMALL LETTER UPSILON WITH TONOS
    ('\u{03ce}', "o"), // ώ GREEK SMALL LETTER OMEGA WITH TONOS
    ('\u{03d0}', "b"), // ϐ GREEK BETA SYMBOL
    ('\u{03d1}', "th"), // ϑ GREEK THETA SYMBOL
    ('\u{03d2}', "y"), // ϒ GREEK UPSILON WITH HOOK SYMBOL
    ('\u{03d3}', "y"), // ϓ GREEK UPSILON WITH ACUTE AND HOOK SYMBOL
    ('\u{03d4}', "y"), // ϔ GREEK UPSILON WITH DIAERESIS AND HOOK SYMBOL
    ('\u{03d5}', "ph"), // ϕ GREEK PHI SYMBOL
    ('\u{03d6}', "p"), // ϖ GREEK PI SYMBOL
    ('\u{03d9}', "q"), // ϙ GREEK SMALL LETTER ARCHAIC KOPPA
    ('\u{03db}', "st"), // ϛ GREEK SMALL LETTER STIGMA
    ('\u{03dd}', "w"), // ϝ GREEK SMALL LETTER DIGAMMA
    ('\u{03df}', "q"), // ϟ GREEK SMALL LETTER KOPPA
    ('\u{03e1}', "s"), // ϡ GREEK SMALL LETTER SAMPI
    ('\u{03e3}', "sh"), // ϣ COPTIC SMALL LETTER SHEI
    ('\u{03e5}', "f"), // ϥ COPTIC SMALL LETTER FEI
    ('\u{03e7}', "x"), // ϧ COPTIC SMALL LETTER KHEI
    ('\u{03e9}', "h"), // ϩ COPTIC SMALL LETTER HORI
    ('\u{03eb}', "j"), // ϫ COPTIC SMALL LETTER GANGIA
    ('\u{03ed}', "c"), // ϭ COPTIC SMALL LETTER SHIMA
    ('\u{03ef}', "ti"), // ϯ COPTIC SMALL LETTER DEI
    ('\u{03f0}', "k"), // ϰ GREEK KAPPA SYMBOL
    ('\u{03f1}', "r"), // ϱ GREEK RHO SYMBOL
    ('\u{03f2}', "s"), // ϲ GREEK LUNATE SIGMA SYMBOL
    ('\u{03f3}', "j"), // ϳ GREEK LETTER YOT
    ('\u{03f5}', "e"), // ϵ GREEK LUNATE EPSILON SYMBOL
    ('\u{03f8}', "sh"), // ϸ GREEK SMALL LETTER SHO
    ('\u{03fb}', "s"), // ϻ GREEK SMALL LETTER SAN
    ('\u{1d00}', "a"), // ᴀ LATIN LETTER SMALL CAPITAL A
    ('\u{1d01}', "ae"), // ᴁ LATIN LETTER SMALL CAPITAL AE
    ('\u{1d02}', "ae"), // ᴂ LATIN SMALL LETTER TURNED AE
    ('\u{1d03}', "b"), // ᴃ LATIN LETTER SMALL CAPITAL BARRED B
    ('\u{1d04}', "c"), // ᴄ LATIN LETTER SMALL CAPITAL C
    ('\u{1d05}', "d"), // ᴅ LATIN LETTER SMALL CAPITAL D
    ('\u{1d06}', "d"), // ᴆ LATIN LETTER SMALL CAPITAL ETH
    ('\u{1d07}', "e"), // ᴇ LATIN LETTER SMALL CAPITAL E
    ('\u{1d08}', "e"), // ᴈ LATIN SMALL LETTER TURNED OPEN E
    ('\u{1d09}', "i"), // ᴉ LATIN SMALL LETTER TURNED I
    ('\u{1d0a}', "j"), // ᴊ LATIN LETTER SMALL CAPITAL J
    ('\u{1d0b}', "k"), // ᴋ LATIN LETTER SMALL CAPITAL K
    ('\u{1d0c}', "l"), // ᴌ LATIN LETTER SMALL CAPITAL L WITH STROKE
    ('\u{1d0d}', "m"), // ᴍ LATIN LETTER SMALL CAPITAL M
    ('\u{1d0e}', "n"), // ᴎ LATIN LETTER SMALL CAPITAL REVERSED N
    ('\u{1d0f}', "o"), // ᴏ LATIN LETTER SMALL CAPITAL O
    ('\u{1d10}', "o"), // ᴐ LATIN LETTER SMALL CAPITAL OPEN O
    ('\u{1d11}', "o"), // ᴑ LATIN SMALL LETTER SIDEWAYS O
    ('\u{1d12}', "o"), // ᴒ LATIN SMALL LETTER SIDEWAYS OPEN O
    ('\u{1d13}', "o"), // ᴓ LATIN SMALL LETTER SIDEWAYS O WITH STROKE
    ('\u{1d14}', "oe"), // ᴔ LATIN SMALL LETTER TURNED OE
    ('\u{1d15}', "ou"), // ᴕ LATIN LETTER SMALL CAPITAL OU
    ('\u{1d16}', "o"), // ᴖ LATIN SMALL LETTER TOP HALF O
    ('\u{1d17}', "o"), // ᴗ LATIN SMALL LETTER BOTTOM HALF O
    ('\u{1d18}', "p"), // ᴘ LATIN LETTER SMALL CAPITAL P
    ('\u{1d19}', "r"), // ᴙ LATIN LETTER SMALL CAPITAL REVERSED R
    ('\u{1d1a}', "r"), // ᴚ LATIN LETTER SMALL CAPITAL TURNED R
    ('\u{1d1b}', "t"), // ᴛ LATIN LETTER SMALL CAPITAL T
    ('\u{1d1c}', "u"), // ᴜ LATIN LETTER SMALL CAPITAL U
    ('\u{1d1d}', "u"), // ᴝ LATIN SMALL LETTER SIDEWAYS U
    ('\u{1d1e}', "u"), // ᴞ LATIN SMALL LETTER SIDEWAYS DIAERESIZED U
    ('\u{1d1f}', "w"), // ᴟ LATIN SMALL LETTER SIDEWAYS TURNED M
    ('\u{1d20}', "v"), // ᴠ LATIN LETTER SMALL CAPITAL V
    ('\u{1d21}', "w"), // ᴡ LATIN LETTER SMALL CAPITAL W
    ('\u{1d22}', "z"), // ᴢ LATIN LETTER SMALL CAPITAL Z
    ('\u{1d23}', "zh"), // ᴣ LATIN LETTER SMALL CAPITAL EZH
    ('\u{1d26}', "g"), // ᴦ GREEK LETTER SMALL CAPITAL GAMMA
    ('\u{1d27}', "l"), // ᴧ GREEK LETTER SMALL CAPITAL LAMDA
    ('\u{1d28}', "p"), // ᴨ GREEK LETTER SMALL CAPITAL PI
    ('\u{1d29}', "r"), // ᴩ GREEK LETTER SMALL CAPITAL RHO
    ('\u{1d2a}', "ps"), // ᴪ GREEK LETTER SMALL CAPITAL PSI
    ('\u{1d2b}', "l"), // ᴫ CYRILLIC LETTER SMALL CAPITAL EL
    ('\u{1d2c}', "a"), // ᴬ MODIFIER LETTER CAPITAL A
    ('\u{1d2d}', "ae"), // ᴭ MODIFIER LETTER CAPITAL AE
    ('\u{1d2e}', "b"), // ᴮ MODIFIER LETTER CAPITAL B
    ('\u{1d2f}', "b"), // ᴯ MODIFIER LETTER CAPITAL BARRED B
    ('\u{1d30}', "d"), // ᴰ MODIFIER LETTER CAPITAL D
    ('\u{1d31}', "e"), // ᴱ MODIFIER LETTER CAPITAL E
    ('\u{1d32}', "e"), // ᴲ MODIFIER LETTER CAPITAL REVERSED E
    ('\u{1d33}', "g"), // ᴳ MODIFIER LETTER CAPITAL G
    ('\u{1d34}', "h"), // ᴴ MODIFIER LETTER CAPITAL H
    ('\u{1d35}', "i"), // ᴵ MODIFIER LETTER CAPITAL I
    ('\u{1d36}', "j"), // ᴶ MODIFIER LETTER CAPITAL J
    ('\u{1d37}', "k"), // ᴷ MODIFIER LETTER CAPITAL K
    ('\u{1d38}', "l"), // ᴸ MODIFIER LETTER CAPITAL L
    ('\u{1d39}', "m"), // ᴹ MODIFIER LETTER CAPITAL M
    ('\u{1d3a}', "n"), // ᴺ MODIFIER LETTER CAPITAL N
    ('\u{1d3b}', "n"), // ᴻ MODIFIER LETTER CAPITAL REVERSED N
    ('\u{1d3c}', "o"), // ᴼ MODIFIER LETTER CAPITAL O
    ('\u{1d3d}', "ou"), // ᴽ MODIFIER LETTER CAPITAL OU
    ('\u{1d3e}', "p"), // ᴾ MODIFIER LETTER CAPITAL P
    ('\u{1d3f}', "r"), // ᴿ MODIFIER LETTER CAPITAL R
    ('\u{1d40}', "t"), // ᵀ MODIFIER LETTER CAPITAL T
    ('\u{1d41}', "u"), // ᵁ MODIFIER LETTER CAPITAL U
    ('\u{1d42}', "w"), // ᵂ MODIFIER LETTER CAPITAL W
    ('\u{1d43}', "a"), // ᵃ MODIFIER LETTER SMALL A
    ('\u{1d44}', "a"), // ᵄ MODIFIER LETTER SMALL TURNED A
    ('\u{1d45}', "a"), // ᵅ MODIFIER LETTER SMALL ALPHA
    ('\u{1d46}', "ae"), // ᵆ MODIFIER LETTER SMALL TURNED AE
    ('\u{1d47}', "b"), // ᵇ MODIFIER LETTER SMALL B
    ('\u{1d48}', "d"), // ᵈ MODIFIER LETTER SMALL D
    ('\u{1d49}', "e"), // ᵉ MODIFIER LETTER SMALL E
    ('\u{1d4a}', "e"), // ᵊ MODIFIER LETTER SMALL SCHWA
    ('\u{1d4b}', "e"), // ᵋ MODIFIER LETTER SMALL OPEN E
    ('\u{1d4c}', "e"), // ᵌ MODIFIER LETTER SMALL TURNED OPEN E
    ('\u{1d4d}', "g"), // ᵍ MODIFIER LETTER SMALL G
    ('\u{1d4e}', "i"), // ᵎ MODIFIER LETTER SMALL TURNED I
    ('\u{1d4f}', "k"), // ᵏ MODIFIER LETTER SMALL K
    ('\u{1d50}', "m"), // ᵐ MODIFIER LETTER SMALL M
    ('\u{1d51}', "ng"), // ᵑ MODIFIER LETTER SMALL ENG
    ('\u{1d52}', "o"), // ᵒ MODIFIER LETTER SMALL O
    ('\u{1d53}', "o"), // ᵓ MODIFIER LETTER SMALL OPEN O
    ('\u{1d54}', "o"), // ᵔ MODIFIER LETTER SMALL TOP HALF O
    ('\u{1d55}', "o"), // ᵕ MODIFIER LETTER SMALL BOTTOM HALF O
    ('\u{1d56}', "p"), // ᵖ MODIFIER LETTER SMALL P
    ('\u{1d57}', "t"), // ᵗ MODIFIER LETTER SMALL T
    ('\u{1d58}', "u"), // ᵘ MODIFIER LETTER SMALL U
    ('\u{1d59}', "u"), // ᵙ MODIFIER LETTER SMALL SIDEWAYS U
    ('\u{1d5a}', "w"), // ᵚ MODIFIER LETTER SMALL TURNED M
    ('\u{1d5b}', "v"), // ᵛ MODIFIER LETTER SMALL V
    ('\u{1d5d}', "v"), // ᵝ MODIFIER LETTER SMALL BETA
    ('\u{1d5e}', "g"), // ᵞ MODIFIER LETTER SMALL GREEK GAMMA
    ('\u{1d5f}', "d"), // ᵟ MODIFIER LETTER SMALL DELTA
    ('\u{1d60}', "f"), // ᵠ MODIFIER LETTER SMALL GREEK PHI
    ('\u{1d61}', "ch"), // ᵡ MODIFIER LETTER SMALL CHI
    ('\u{1d62}', "i"), // ᵢ LATIN SUBSCRIPT SMALL LETTER I
    ('\u{1d63}', "r"), // ᵣ LATIN SUBSCRIPT SMALL LETTER R
    ('\u{1d64}', "u"), // ᵤ LATIN SUBSCRIPT SMALL LETTER U
    ('\u{1d65}', "v"), // ᵥ LATIN SUBSCRIPT SMALL LETTER V
    ('\u{1d66}', "v"), // ᵦ GREEK SUBSCRIPT SMALL LETTER BETA
    ('\u{1d67}', "g"), // ᵧ GREEK SUBSCRIPT SMALL LETTER GAMMA
    ('\u{1d68}', "r"), // ᵨ GREEK SUBSCRIPT SMALL LETTER RHO
    ('\u{1d69}', "f"), // ᵩ GREEK SUBSCRIPT SMALL LETTER PHI
    ('\u{1d6a}', "ch"), // ᵪ GREEK SUBSCRIPT SMALL LETTER CHI
    ('\u{1d6b}', "ue"), // ᵫ LATIN SMALL LETTER UE
    ('\u{1d6c}', "b"), // ᵬ LATIN SMALL LETTER B WITH MIDDLE TILDE
    ('\u{1d6d}', "d"), // ᵭ LATIN SMALL LETTER D WITH MIDDLE TILDE
    ('\u{1d6e}', "f"), // ᵮ LATIN SMALL LETTER F WITH MIDDLE TILDE
    ('\u{1d6f}', "m"), // ᵯ LATIN SMALL LETTER M WITH MIDDLE TILDE
    ('\u{1d70}', "n"), // ᵰ LATIN SMALL LETTER N WITH MIDDLE TILDE
    ('\u{1d71}', "p"), // ᵱ LATIN SMALL LETTER P WITH MIDDLE TILDE
    ('\u{1d72}', "r"), // ᵲ LATIN SMALL LETTER R WITH MIDDLE TILDE
    ('\u{1d73}', "r"), // ᵳ LATIN SMALL LETTER R WITH FISHHOOK AND MIDDLE TILDE
    ('\u{1d74}', "s"), // ᵴ LATIN SMALL LETTER S WITH MIDDLE TILDE
    ('\u{1d75}', "t"), // ᵵ LATIN SMALL LETTER T WITH MIDDLE TILDE
    ('\u{1d76}', "z"), // ᵶ LATIN SMALL LETTER Z WITH MIDDLE TILDE
    ('\u{1d77}', "g"), // ᵷ LATIN SMALL LETTER TURNED G
    ('\u{1d78}', "n"), // ᵸ MODIFIER LETTER CYRILLIC EN
    ('\u{1d79}', "g"), // ᵹ LATIN SMALL LETTER INSULAR G
    ('\u{1d7a}', "th"), // ᵺ LATIN SMALL LETTER TH WITH STRIKETHROUGH
    ('\u{1d7b}', "i"), // ᵻ LATIN SMALL CAPITAL LETTER I WITH STROKE
    ('\u{1d7c}', "i"), // ᵼ LATIN SMALL LETTER IOTA WITH STROKE
    ('\u{1d7d}', "p"), // ᵽ LATIN SMALL LETTER P WITH STROKE
    ('\u{1d7e}', "u"), // ᵾ LATIN SMALL CAPITAL LETTER U WITH STROKE
    ('\u{1d7f}', "u"), // ᵿ LATIN SMALL LETTER UPSILON WITH STROKE
    ('\u{1d80}', "b"), // ᶀ LATIN SMALL LETTER B WITH PALATAL HOOK
    ('\u{1d81}', "d"), // ᶁ LATIN SMALL LETTER D WITH PALATAL HOOK
    ('\u{1d82}', "f"), // ᶂ LATIN SMALL LETTER F WITH PALATAL HOOK
    ('\u{1d83}', "g"), // ᶃ LATIN SMALL LETTER G WITH PALATAL HOOK
    ('\u{1d84}', "k"), // ᶄ LATIN SMALL LETTER K WITH PALATAL HOOK
    ('\u{1d85}', "l"), // ᶅ LATIN SMALL LETTER L WITH PALATAL HOOK
    ('\u{1d86}', "m"), // ᶆ LATIN SMALL LETTER M WITH PALATAL HOOK
    ('\u{1d87}', "n"), // ᶇ LATIN SMALL LETTER N WITH PALATAL HOOK
    ('\u{1d88}', "p"), // ᶈ LATIN SMALL LETTER P WITH PALATAL HOOK
    ('\u{1d89}', "r"), // ᶉ LATIN SMALL LETTER R WITH PALATAL HOOK
    ('\u{1d8a}', "s"), // ᶊ LATIN SMALL LETTER S WITH PALATAL HOOK
    ('\u{1d8b}', "sh"), // ᶋ LATIN SMALL LETTER ESH WITH PALATAL HOOK
    ('\u{1d8c}', "v"), // ᶌ LATIN SMALL LETTER V WITH PALATAL HOOK
    ('\u{1d8d}', "x"), // ᶍ LATIN SMALL LETTER X WITH PALATAL HOOK
    ('\u{1d8e}', "z"), // ᶎ LATIN SMALL LETTER Z WITH PALATAL HOOK
    ('\u{1d8f}', "a"), // ᶏ LATIN SMALL LETTER A WITH RETROFLEX HOOK
    ('\u{1d90}', "a"), // ᶐ LATIN SMALL LETTER ALPHA WITH RETROFLEX HOOK
    ('\u{1d91}', "d"), // ᶑ LATIN SMALL LETTER D WITH HOOK AND TAIL
    ('\u{1d92}', "e"), // ᶒ LATIN SMALL LETTER E WITH RETROFLEX HOOK
    ('\u{1d93}', "e"), // ᶓ LATIN SMALL LETTER OPEN E WITH RETROFLEX HOOK
    ('\u{1d94}', "e"), // ᶔ LATIN SMALL LETTER REVERSED OPEN E WITH RETROFLEX HOOK
    ('\u{1d95}', "e"), // ᶕ LATIN SMALL LETTER SCHWA WITH RETROFLEX HOOK
    ('\u{1d96}', "i"), // ᶖ LATIN SMALL LETTER I WITH RETROFLEX HOOK
    ('\u{1d97}', "o"), // ᶗ LATIN SMALL LETTER OPEN O WITH RETROFLEX HOOK
    ('\u{1d98}', "sh"), // ᶘ LATIN SMALL LETTER ESH WITH RETROFLEX HOOK
    ('\u{1d99}', "u"), // ᶙ LATIN SMALL LETTER U WITH RETROFLEX HOOK
    ('\u{1d9a}', "zh"), // ᶚ LATIN SMALL LETTER EZH WITH RETROFLEX HOOK
    ('\u{1d9b}', "a"), // ᶛ MODIFIER LETTER SMALL TURNED ALPHA
    ('\u{1d9c}', "c"), // ᶜ MODIFIER LETTER SMALL C
    ('\u{1d9d}', "c"), // ᶝ MODIFIER LETTER SMALL C WITH CURL
    ('\u{1d9e}', "d"), // ᶞ MODIFIER LETTER SMALL ETH
    ('\u{1d9f}', "e"), // ᶟ MODIFIER LETTER SMALL REVERSED OPEN E
    ('\u{1da0}', "f"), // ᶠ MODIFIER LETTER SMALL F
    ('\u{1da1}', "j"), // ᶡ MODIFIER LETTER SMALL DOTLESS J WITH STROKE
    ('\u{1da2}', "g"), // ᶢ MODIFIER LETTER SMALL SCRIPT G
    ('\u{1da3}', "y"), // ᶣ MODIFIER LETTER SMALL TURNED H
    ('\u{1da4}', "i"), // ᶤ MODIFIER LETTER SMALL I WITH STROKE
    ('\u{1da5}', "i"), // ᶥ MODIFIER LETTER SMALL IOTA
    ('\u{1da6}', "i"), // ᶦ MODIFIER LETTER SMALL CAPITAL I
    ('\u{1da7}', "i"), // ᶧ MODIFIER LETTER SMALL CAPITAL I WITH STROKE
    ('\u{1da8}', "j"), // ᶨ MODIFIER LETTER SMALL J WITH CROSSED-TAIL
    ('\u{1da9}', "l"), // ᶩ MODIFIER LETTER SMALL L WITH RETROFLEX HOOK
    ('\u{1daa}', "l"), // ᶪ MODIFIER LETTER SMALL L WITH PALATAL HOOK
    ('\u{1dab}', "l"), // ᶫ MODIFIER LETTER SMALL CAPITAL L
    ('\u{1dac}', "m"), // ᶬ MODIFIER LETTER SMALL M WITH HOOK
    ('\u{1dad}', "w"), // ᶭ MODIFIER LETTER SMALL TURNED M WITH LONG LEG
    ('\u{1dae}', "n"), // ᶮ MODIFIER LETTER SMALL N WITH LEFT HOOK
    ('\u{1daf}', "n"), // ᶯ MODIFIER LETTER SMALL N WITH RETROFLEX HOOK
    ('\u{1db0}', "n"), // ᶰ MODIFIER LETTER SMALL CAPITAL N
    ('\u{1db1}', "o"), // ᶱ MODIFIER LETTER SMALL BARRED O
    ('\u{1db2}', "f"), // ᶲ MODIFIER LETTER SMALL PHI
    ('\u{1db3}', "s"), // ᶳ MODIFIER LETTER SMALL S WITH HOOK
    ('\u{1db4}', "sh"), // ᶴ MODIFIER LETTER SMALL ESH
    ('\u{1db5}', "t"), // ᶵ MODIFIER LETTER SMALL T WITH PALATAL HOOK
    ('\u{1db6}', "u"), // ᶶ MODIFIER LETTER SMALL U BAR
    ('\u{1db7}', "u"), // ᶷ MODIFIER LETTER SMALL UPSILON
    ('\u{1db8}', "u"), // ᶸ MODIFIER LETTER SMALL CAPITAL U
    ('\u{1db9}', "v"), // ᶹ MODIFIER LETTER SMALL V WITH HOOK
    ('\u{1dba}', "a"), // ᶺ MODIFIER LETTER SMALL TURNED V
    ('\u{1dbb}', "z"), // ᶻ MODIFIER LETTER SMALL Z
    ('\u{1dbc}', "z"), // ᶼ MODIFIER LETTER SMALL Z WITH RETROFLEX HOOK
    ('\u{1dbd}', "z"), // ᶽ MODIFIER LETTER SMALL Z WITH CURL
    ('\u{1dbe}', "zh"), // ᶾ MODIFIER LETTER SMALL EZH
    ('\u{1dbf}', "th"), // ᶿ MODIFIER LETTER SMALL THETA
    ('\u{1e01}', "a"), // ḁ LATIN SMALL LETTER A WITH RING BELOW
    ('\u{1e03}', "b"), // ḃ LATIN SMALL LETTER B WITH DOT ABOVE
    ('\u{1e05}', "b"), // ḅ LATIN SMALL LETTER B WITH DOT BELOW
    ('\u{1e07}', "b"), // ḇ LATIN SMALL LETTER B WITH LINE BELOW
    ('\u{1e09}', "c"), // ḉ LATIN SMALL LETTER C WITH CEDILLA AND ACUTE
    ('\u{1e0b}', "d"), // ḋ LATIN SMALL LETTER D WITH DOT ABOVE
    ('\u{1e0d}', "d"), // ḍ LATIN SMALL LETTER D WITH DOT BELOW
    ('\u{1e0f}', "d"), // ḏ LATIN SMALL LETTER D WITH LINE BELOW
    ('\u{1e11}', "d"), // ḑ LATIN SMALL LETTER D WITH CEDILLA
    ('\u{1e13}', "d"), // ḓ LATIN SMALL LETTER D WITH CIRCUMFLEX BELOW
    ('\u{1e15}', "e"), // ḕ LATIN SMALL LETTER E WITH MACRON AND GRAVE
    ('\u{1e17}', "e"), // ḗ LATIN SMALL LETTER E WITH MACRON AND ACUTE
    ('\u{1e19}', "e"), // ḙ LATIN SMALL LETTER E WITH CIRCUMFLEX BELOW
    ('\u{1e1b}', "e"), // ḛ LATIN SMALL LETTER E WITH TILDE BELOW
    ('\u{1e1d}', "e"), // ḝ LATIN SMALL LETTER E WITH CEDILLA AND BREVE
    ('\u{1e1f}', "f"), // ḟ LATIN SMALL LETTER F WITH DOT ABOVE
    ('\u{1e21}', "g"), // ḡ LATIN SMALL LETTER G WITH MACRON
    ('\u{1e23}', "h"), // ḣ LATIN SMALL LETTER H WITH DOT ABOVE
    ('\u{1e25}', "h"), // ḥ LATIN SMALL LETTER H WITH DOT BELOW
    ('\u{1e27}', "h"), // ḧ LATIN SMALL LETTER H WITH DIAERESIS
    ('\u{1e29}', "h"), // ḩ LATIN SMALL LETTER H WITH CEDILLA
    ('\u{1e2b}', "h"), // ḫ LATIN SMALL LETTER H WITH BREVE BELOW
    ('\u{1e2d}', "i"), // ḭ LATIN SMALL LETTER I WITH TILDE BELOW
    ('\u{1e2f}', "i"), // ḯ LATIN SMALL LETTER I WITH DIAERESIS AND ACUTE
    ('\u{1e31}', "k"), // ḱ LATIN SMALL LETTER K WITH ACUTE
    ('\u{1e33}', "k"), // ḳ LATIN SMALL LETTER K WITH DOT BELOW
    ('\u{1e35}', "k"), // ḵ LATIN SMALL LETTER K WITH LINE BELOW
    ('\u{1e37}', "l"), // ḷ LATIN SMALL LETTER L WITH DOT BELOW
    ('\u{1e39}', "l"), // ḹ LATIN SMALL LETTER L WITH DOT BELOW AND MACRON
    ('\u{1e3b}', "l"), // ḻ LATIN SMALL LETTER L WITH LINE BELOW
    ('\u{1e3d}', "l"), // ḽ LATIN SMALL LETTER L WITH CIRCUMFLEX BELOW
    ('\u{1e3f}', "m"), // ḿ LATIN SMALL LETTER M WITH ACUTE
    ('\u{1e41}', "m"), // ṁ LATIN SMALL LETTER M WITH DOT ABOVE
    ('\u{1e43}', "m"), // ṃ LATIN SMALL LETTER M WITH DOT BELOW
    ('\u{1e45}', "n"), // ṅ LATIN SMALL LETTER N WITH DOT ABOVE
    ('\u{1e47}', "n"), // ṇ LATIN SMALL LETTER N WITH DOT BELOW
    ('\u{1e49}', "n"), // ṉ LATIN SMALL LETTER N WITH LINE BELOW
    ('\u{1e4b}', "n"), // ṋ LATIN SMALL LETTER N WITH CIRCUMFLEX BELOW
    ('\u{1e4d}', "o"), // ṍ LATIN SMALL LETTER O WITH TILDE AND ACUTE
    ('\u{1e4f}', "o"), // ṏ LATIN SMALL LETTER O WITH TILDE AND DIAERESIS
    ('\u{1e51}', "o"), // ṑ LATIN SMALL LETTER O WITH MACRON AND GRAVE
    ('\u{1e53}', "o"), // ṓ LATIN SMALL LETTER O WITH MACRON AND ACUTE
    ('\u{1e55}', "p"), // ṕ LATIN SMALL LETTER P WITH ACUTE
    ('\u{1e57}', "p"), // ṗ LATIN SMALL LETTER P WITH DOT ABOVE
    ('\u{1e59}', "r"), // ṙ LATIN SMALL LETTER R WITH DOT ABOVE
    ('\u{1e5b}', "r"), // ṛ LATIN SMALL LETTER R WITH DOT BELOW
    ('\u{1e5d}', "r"), // ṝ LATIN SMALL LETTER R WITH DOT BELOW AND MACRON
    ('\u{1e5f}', "r"), // ṟ LATIN SMALL LETTER R WITH LINE BELOW
    ('\u{1e61}', "s"), // ṡ LATIN SMALL LETTER S WITH DOT ABOVE
    ('\u{1e63}', "s"), // ṣ LATIN SMALL LETTER S WITH DOT BELOW
    ('\u{1e65}', "s"), // ṥ LATIN SMALL LETTER S WITH ACUTE AND DOT ABOVE
    ('\u{1e67}', "s"), // ṧ LATIN SMALL LETTER S WITH CARON AND DOT ABOVE
    ('\u{1e69}', "s"), // ṩ LATIN SMALL LETTER S WITH DOT BELOW AND DOT ABOVE
    ('\u{1e6b}', "t"), // ṫ LATIN SMALL LETTER T WITH DOT ABOVE
    ('\u{1e6d}', "t"), // ṭ LATIN SMALL LETTER T WITH DOT BELOW
    ('\u{1e6f}', "t"), // ṯ LATIN SMALL LETTER T WITH LINE BELOW
    ('\u{1e71}', "t"), // ṱ LATIN SMALL LETTER T WITH CIRCUMFLEX BELOW
    ('\u{1e73}', "u"), // ṳ LATIN SMALL LETTER U WITH DIAERESIS BELOW
    ('\u{1e75}', "u"), // ṵ LATIN SMALL LETTER U WITH TILDE BELOW
    ('\u{1e77}', "u"), // ṷ LATIN SMALL LETTER U WITH CIRCUMFLEX BELOW
    ('\u{1e79}', "u"), // ṹ LATIN SMALL LETTER U WITH TILDE AND ACUTE
    ('\u{1e7b}', "u"), // ṻ LATIN SMALL LETTER U WITH MACRON AND DIAERESIS
    ('\u{1e7d}', "v"), // ṽ LATIN SMALL LETTER V WITH TILDE
    ('\u{1e7f}', "v"), // ṿ LATIN SMALL LETTER V WITH DOT BELOW
    ('\u{1e81}', "w"), // ẁ LATIN SMALL LETTER W WITH GRAVE
    ('\u{1e83}', "w"), // ẃ LATIN SMALL LETTER W WITH ACUTE
    ('\u{1e85}', "w"), // ẅ LATIN SMALL LETTER W WITH DIAERESIS
    ('\u{1e87}', "w"), // ẇ LATIN SMALL LETTER W WITH DOT ABOVE
    ('\u{1e89}', "w"), // ẉ LATIN SMALL LETTER W WITH DOT BELOW
    ('\u{1e8b}', "x"), // ẋ LATIN SMALL LETTER X WITH DOT ABOVE
    ('\u{1e8d}', "x"), // ẍ LATIN SMALL LETTER X WITH DIAERESIS
    ('\u{1e8f}', "y"), // ẏ LATIN SMALL LETTER Y WITH DOT ABOVE
    ('\u{1e91}', "z"), // ẑ LATIN SMALL LETTER Z WITH CIRCUMFLEX
    ('\u{1e93}', "z"), // ẓ LATIN SMALL LETTER Z WITH DOT BELOW
    ('\u{1e95}', "z"), // ẕ LATIN SMALL LETTER Z WITH LINE BELOW
    ('\u{1e96}', "h"), // ẖ LATIN SMALL LETTER H WITH LINE BELOW
    ('\u{1e97}', "t"), // ẗ LATIN SMALL LETTER T WITH DIAERESIS
    ('\u{1e98}', "w"), // ẘ LATIN SMALL LETTER W WITH RING ABOVE
    ('\u{1e99}', "y"), // ẙ LATIN SMALL LETTER Y WITH RING ABOVE
    ('\u{1e9b}', "s"), // ẛ LATIN SMALL LETTER LONG S WITH DOT ABOVE
    ('\u{1e9f}', "d"), // ẟ LATIN SMALL LETTER DELTA
    ('\u{1ea1}', "a"), // ạ LATIN SMALL LETTER A WITH DOT BELOW
    ('\u{1ea3}', "a"), // ả LATIN SMALL LETTER A WITH HOOK ABOVE
    ('\u{1ea5}', "a"), // ấ LATIN SMALL LETTER A WITH CIRCUMFLEX AND ACUTE
    ('\u{1ea7}', "a"), // ầ LATIN SMALL LETTER A WITH CIRCUMFLEX AND GRAVE
    ('\u{1ea9}', "a"), // ẩ LATIN SMALL LETTER A WITH CIRCUMFLEX AND HOOK ABOVE
    ('\u{1eab}', "a"), // ẫ LATIN SMALL LETTER A WITH CIRCUMFLEX AND TILDE
    ('\u{1ead}', "a"), // ậ LATIN SMALL LETTER A WITH CIRCUMFLEX AND DOT BELOW
    ('\u{1eaf}', "a"), // ắ LATIN SMALL LETTER A WITH BREVE AND ACUTE
    ('\u{1eb1}', "a"), // ằ LATIN SMALL LETTER A WITH BREVE AND GRAVE
    ('\u{1eb3}', "a"), // ẳ LATIN SMALL LETTER A WITH BREVE AND HOOK ABOVE
    ('\u{1eb5}', "a"), // ẵ LATIN SMALL LETTER A WITH BREVE AND TILDE
    ('\u{1eb7}', "a"), // ặ LATIN SMALL LETTER A WITH BREVE AND DOT BELOW
    ('\u{1eb9}', "e"), // ẹ LATIN SMALL LETTER E WITH DOT BELOW
    ('\u{1ebb}', "e"), // ẻ LATIN SMALL LETTER E WITH HOOK ABOVE
    ('\u{1ebd}', "e"), // ẽ LATIN SMALL LETTER E WITH TILDE
    ('\u{1ebf}', "e"), // ế LATIN SMALL LETTER E WITH CIRCUMFLEX AND ACUTE
    ('\u{1ec1}', "e"), // ề LATIN SMALL LETTER E WITH CIRCUMFLEX AND GRAVE
    ('\u{1ec3}', "e"), // ể LATIN SMALL LETTER E WITH CIRCUMFLEX AND HOOK ABOVE
    ('\u{1ec5}', "e"), // ễ LATIN SMALL LETTER E WITH CIRCUMFLEX AND TILDE
    ('\u{1ec7}', "e"), // ệ LATIN SMALL LETTER E WITH CIRCUMFLEX AND DOT BELOW
    ('\u{1ec9}', "i"), // ỉ LATIN SMALL LETTER I WITH HOOK ABOVE
    ('\u{1ecb}', "i"), // ị LATIN SMALL LETTER I WITH DOT BELOW
    ('\u{1ecd}', "o"), // ọ LATIN SMALL LETTER O WITH DOT BELOW
    ('\u{1ecf}', "o"), // ỏ LATIN SMALL LETTER O WITH HOOK ABOVE
    ('\u{1ed1}', "o"), // ố LATIN SMALL LETTER O WITH CIRCUMFLEX AND ACUTE
    ('\u{1ed3}', "o"), // ồ LATIN SMALL LETTER O WITH CIRCUMFLEX AND GRAVE
    ('\u{1ed5}', "o"), // ổ LATIN SMALL LETTER O WITH CIRCUMFLEX AND HOOK ABOVE
    ('\u{1ed7}', "o"), // ỗ LATIN SMALL LETTER O WITH CIRCUMFLEX AND TILDE
    ('\u{1ed9}', "o"), // ộ LATIN SMALL LETTER O WITH CIRCUMFLEX AND DOT BELOW
    ('\u{1edb}', "o"), // ớ LATIN SMALL LETTER O WITH HORN AND ACUTE
    ('\u{1edd}', "o"), // ờ LATIN SMALL LETTER O WITH HORN AND GRAVE
    ('\u{1edf}', "o"), // ở LATIN SMALL LETTER O WITH HORN AND HOOK ABOVE
    ('\u{1ee1}', "o"), // ỡ LATIN SMALL LETTER O WITH HORN AND TILDE
    ('\u{1ee3}', "o"), // ợ LATIN SMALL LETTER O WITH HORN AND DOT BELOW
    ('\u{1ee5}', "u"), // ụ LATIN SMALL LETTER U WITH DOT BELOW
    ('\u{1ee7}', "u"), // ủ LATIN SMALL LETTER U WITH HOOK ABOVE
    ('\u{1ee9}', "u"), // ứ LATIN SMALL LETTER U WITH HORN AND ACUTE
    ('\u{1eeb}', "u"), // ừ LATIN SMALL LETTER U WITH HORN AND GRAVE
    ('\u{1eed}', "u"), // ử LATIN SMALL LETTER U WITH HORN AND HOOK ABOVE
    ('\u{1eef}', "u"), // ữ LATIN SMALL LETTER U WITH HORN AND TILDE
    ('\u{1ef1}', "u"), // ự LATIN SMALL LETTER U WITH HORN AND DOT BELOW
    ('\u{1ef3}', "y"), // ỳ LATIN SMALL LETTER Y WITH GRAVE
    ('\u{1ef5}', "y"), // ỵ LATIN SMALL LETTER Y WITH DOT BELOW
    ('\u{1ef7}', "y"), // ỷ LATIN SMALL LETTER Y WITH HOOK ABOVE
    ('\u{1ef9}', "y"), // ỹ LATIN SMALL LETTER Y WITH TILDE
    ('\u{1efb}', "ll"), // ỻ LATIN SMALL LETTER MIDDLE-WELSH LL
    ('\u{1efd}', "v"), // ỽ LATIN SMALL LETTER MIDDLE-WELSH V
    ('\u{1eff}', "y"), // ỿ LATIN SMALL LETTER Y WITH LOOP
    ('\u{2c61}', "l"), // ⱡ LATIN SMALL LETTER L WITH DOUBLE BAR
    ('\u{2c65}', "a"), // ⱥ LATIN SMALL LETTER A WITH STROKE
    ('\u{2c66}', "t"), // ⱦ LATIN SMALL LETTER T WITH DIAGONAL STROKE
    ('\u{2c68}', "h"), // ⱨ LATIN SMALL LETTER H WITH DESCENDER
    ('\u{2c6a}', "k"), // ⱪ LATIN SMALL LETTER K WITH DESCENDER
    ('\u{2c6c}', "z"), // ⱬ LATIN SMALL LETTER Z WITH DESCENDER
    ('\u{2c71}', "v"), // ⱱ LATIN SMALL LETTER V WITH RIGHT HOOK
    ('\u{2c73}', "w"), // ⱳ LATIN SMALL LETTER W WITH HOOK
    ('\u{2c74}', "v"), // ⱴ LATIN SMALL LETTER V WITH CURL
    ('\u{2c76}', "h"), // ⱶ LATIN SMALL LETTER HALF H
    ('\u{2c77}', "o"), // ⱷ LATIN SMALL LETTER TAILLESS PHI
    ('\u{2c78}', "e"), // ⱸ LATIN SMALL LETTER E WITH NOTCH
    ('\u{2c79}', "r"), // ⱹ LATIN SMALL LETTER TURNED R WITH TAIL
    ('\u{2c7a}', "o"), // ⱺ LATIN SMALL LETTER O WITH LOW RING INSIDE
    ('\u{2c7b}', "e"), // ⱻ LATIN LETTER SMALL CAPITAL TURNED E
    ('\u{2c7c}', "j"), // ⱼ LATIN SUBSCRIPT SMALL LETTER J
    ('\u{2c7d}', "v"), // ⱽ MODIFIER LETTER CAPITAL V
    ('\u{a727}', "h"), // ꜧ LATIN SMALL LETTER HENG
    ('\u{a729}', "tz"), // ꜩ LATIN SMALL LETTER TZ
    ('\u{a730}', "f"), // ꜰ LATIN LETTER SMALL CAPITAL F
    ('\u{a731}', "s"), // ꜱ LATIN LETTER SMALL CAPITAL S
    ('\u{a733}', "aa"), // ꜳ LATIN SMALL LETTER AA
    ('\u{a735}', "ao"), // ꜵ LATIN SMALL LETTER AO
    ('\u{a737}', "au"), // ꜷ LATIN SMALL LETTER AU
    ('\u{a739}', "av"), // ꜹ LATIN SMALL LETTER AV
    ('\u{a73b}', "av"), // ꜻ LATIN SMALL LETTER AV WITH HORIZONTAL BAR
    ('\u{a73d}', "ay"), // ꜽ LATIN SMALL LETTER AY
    ('\u{a747}', "l"), // ꝇ LATIN SMALL LETTER BROKEN L
    ('\u{a74d}', "o"), // ꝍ LATIN SMALL LETTER O WITH LOOP
    ('\u{a74f}', "oo"), // ꝏ LATIN SMALL LETTER OO
    ('\u{a75b}', "r"), // ꝛ LATIN SMALL LETTER R ROTUNDA
    ('\u{a761}', "vy"), // ꝡ LATIN SMALL LETTER VY
    ('\u{a763}', "z"), // ꝣ LATIN SMALL LETTER VISIGOTHIC Z
    ('\u{a769}', "v"), // ꝩ LATIN SMALL LETTER VEND
    ('\u{a77a}', "d"), // ꝺ LATIN SMALL LETTER INSULAR D
    ('\u{a77c}', "f"), // ꝼ LATIN SMALL LETTER INSULAR F
    ('\u{a77f}', "ng"), // ꝿ LATIN SMALL LETTER TURNED INSULAR G
    ('\u{a781}', "ll"), // ꞁ LATIN SMALL LETTER TURNED L
    ('\u{a783}', "r"), // ꞃ LATIN SMALL LETTER INSULAR R
    ('\u{a785}', "s"), // ꞅ LATIN SMALL LETTER INSULAR S
    ('\u{a787}', "t"), // ꞇ LATIN SMALL LETTER INSULAR T
    ('\u{a78e}', "l"), // ꞎ LATIN SMALL LETTER L WITH RETROFLEX HOOK AND BELT
    ('\u{a791}', "n"), // ꞑ LATIN SMALL LETTER N WITH DESCENDER
    ('\u{a793}', "c"), // ꞓ LATIN SMALL LETTER C WITH BAR
    ('\u{a794}', "c"), // ꞔ LATIN SMALL LETTER C WITH PALATAL HOOK
    ('\u{a795}', "h"), // ꞕ LATIN SMALL LETTER H WITH PALATAL HOOK
    ('\u{a797}', "b"), // ꞗ LATIN SMALL LETTER B WITH FLOURISH
    ('\u{a799}', "f"), // ꞙ LATIN SMALL LETTER F WITH STROKE
    ('\u{a79b}', "ae"), // ꞛ LATIN SMALL LETTER VOLAPUK AE
    ('\u{a79d}', "oe"), // ꞝ LATIN SMALL LETTER VOLAPUK OE
    ('\u{a79f}', "ue"), // ꞟ LATIN SMALL LETTER VOLAPUK UE
    ('\u{a7a1}', "g"), // ꞡ LATIN SMALL LETTER G WITH OBLIQUE STROKE
    ('\u{a7a3}', "k"), // ꞣ LATIN SMALL LETTER K WITH OBLIQUE STROKE
    ('\u{a7a5}', "n"), // ꞥ LATIN SMALL LETTER N WITH OBLIQUE STROKE
    ('\u{a7a7}', "r"), // ꞧ LATIN SMALL LETTER R WITH OBLIQUE STROKE
    ('\u{a7a9}', "s"), // ꞩ LATIN SMALL LETTER S WITH OBLIQUE STROKE
    ('\u{a7af}', "q"), // ꞯ LATIN LETTER SMALL CAPITAL Q
    ('\u{a7b5}', "b"), // ꞵ LATIN SMALL LETTER BETA
    ('\u{a7b7}', "o"), // ꞷ LATIN SMALL LETTER OMEGA
    ('\u{a7b9}', "u"), // ꞹ LATIN SMALL LETTER U WITH STROKE
    ('\u{a7bb}', "a"), // ꞻ LATIN SMALL LETTER GLOTTAL A
    ('\u{a7bd}', "i"), // ꞽ LATIN SMALL LETTER GLOTTAL I
    ('\u{a7bf}', "u"), // ꞿ LATIN SMALL LETTER GLOTTAL U
    ('\u{a7c1}', "o"), // ꟁ LATIN SMALL LETTER OLD POLISH O
    ('\u{a7c3}', "w"), // ꟃ LATIN SMALL LETTER ANGLICANA W
    ('\u{a7c8}', "d"), // ꟈ LATIN SMALL LETTER D WITH SHORT STROKE OVERLAY
    ('\u{a7ca}', "s"), // ꟊ LATIN SMALL LETTER S WITH SHORT STROKE OVERLAY
    ('\u{a7d1}', "g"), // ꟑ LATIN SMALL LETTER CLOSED INSULAR G
    ('\u{a7d3}', "thth"), // ꟓ LATIN SMALL LETTER DOUBLE THORN
    ('\u{a7d5}', "ww"), // ꟕ LATIN SMALL LETTER DOUBLE WYNN
    ('\u{a7d7}', "s"), // ꟗ LATIN SMALL LETTER MIDDLE SCOTS S
    ('\u{a7d9}', "s"), // ꟙ LATIN SMALL LETTER SIGMOID S
    ('\u{a7f2}', "c"), // ꟲ MODIFIER LETTER CAPITAL C
    ('\u{a7f3}', "f"), // ꟳ MODIFIER LETTER CAPITAL F
    ('\u{a7f4}', "q"), // ꟴ MODIFIER LETTER CAPITAL Q
    ('\u{a7f6}', "h"), // ꟶ LATIN SMALL LETTER REVERSED HALF H
    ('\u{a7f7}', "i"), // ꟷ LATIN EPIGRAPHIC LETTER SIDEWAYS I
    ('\u{a7f8}', "h"), // ꟸ MODIFIER LETTER CAPITAL H WITH STROKE
    ('\u{a7f9}', "oe"), // ꟹ MODIFIER LETTER SMALL LIGATURE OE
    ('\u{a7fa}', "w"), // ꟺ LATIN LETTER SMALL CAPITAL TURNED M
    ('\u{a7fb}', "f"), // ꟻ LATIN EPIGRAPHIC LETTER REVERSED F
    ('\u{a7fc}', "p"), // ꟼ LATIN EPIGRAPHIC LETTER REVERSED P
    ('\u{a7fd}', "m"), // ꟽ LATIN EPIGRAPHIC LETTER INVERTED M
    ('\u{a7fe}', "i"), // ꟾ LATIN EPIGRAPHIC LETTER I LONGA
    ('\u{ab30}', "a"), // ꬰ LATIN SMALL LETTER BARRED ALPHA
    ('\u{ab31}', "ae"), // ꬱ LATIN SMALL LETTER A REVERSED-SCHWA
    ('\u{ab32}', "e"), // ꬲ LATIN SMALL LETTER BLACKLETTER E
    ('\u{ab33}', "e"), // ꬳ LATIN SMALL LETTER BARRED E
    ('\u{ab34}', "e"), // ꬴ LATIN SMALL LETTER E WITH FLOURISH
    ('\u{ab35}', "f"), // ꬵ LATIN SMALL LETTER LENIS F
    ('\u{ab36}', "g"), // ꬶ LATIN SMALL LETTER SCRIPT G WITH CROSSED-TAIL
    ('\u{ab37}', "l"), // ꬷ LATIN SMALL LETTER L WITH INVERTED LAZY S
    ('\u{ab38}', "l"), // ꬸ LATIN SMALL LETTER L WITH DOUBLE MIDDLE TILDE
    ('\u{ab39}', "l"), // ꬹ LATIN SMALL LETTER L WITH MIDDLE RING
    ('\u{ab3a}', "m"), // ꬺ LATIN SMALL LETTER M WITH CROSSED-TAIL
    ('\u{ab3b}', "n"), // ꬻ LATIN SMALL LETTER N WITH CROSSED-TAIL
    ('\u{ab3c}', "ng"), // ꬼ LATIN SMALL LETTER ENG WITH CROSSED-TAIL
    ('\u{ab3d}', "o"), // ꬽ LATIN SMALL LETTER BLACKLETTER O
    ('\u{ab3e}', "o"), // ꬾ LATIN SMALL LETTER BLACKLETTER O WITH STROKE
    ('\u{ab3f}', "o"), // ꬿ LATIN SMALL LETTER OPEN O WITH STROKE
    ('\u{ab40}', "oe"), // ꭀ LATIN SMALL LETTER INVERTED OE
    ('\u{ab41}', "oe"), // ꭁ LATIN SMALL LETTER TURNED OE WITH STROKE
    ('\u{ab42}', "oe"), // ꭂ LATIN SMALL LETTER TURNED OE WITH HORIZONTAL STROKE
    ('\u{ab43}', "oo"), // ꭃ LATIN SMALL LETTER TURNED O OPEN-O
    ('\u{ab44}', "oo"), // ꭄ LATIN SMALL LETTER TURNED O OPEN-O WITH STROKE
    ('\u{ab45}', "r"), // ꭅ LATIN SMALL LETTER STIRRUP R
    ('\u{ab46}', "r"), // ꭆ LATIN LETTER SMALL CAPITAL R WITH RIGHT LEG
    ('\u{ab47}', "r"), // ꭇ LATIN SMALL LETTER R WITHOUT HANDLE
    ('\u{ab48}', "rr"), // ꭈ LATIN SMALL LETTER DOUBLE R
    ('\u{ab49}', "r"), // ꭉ LATIN SMALL LETTER R WITH CROSSED-TAIL
    ('\u{ab4a}', "rr"), // ꭊ LATIN SMALL LETTER DOUBLE R WITH CROSSED-TAIL
    ('\u{ab4b}', "r"), // ꭋ LATIN SMALL LETTER SCRIPT R
    ('\u{ab4c}', "r"), // ꭌ LATIN SMALL LETTER SCRIPT R WITH RING
    ('\u{ab4d}', "sh"), // ꭍ LATIN SMALL LETTER BASELINE ESH
    ('\u{ab4e}', "u"), // ꭎ LATIN SMALL LETTER U WITH SHORT RIGHT LEG
    ('\u{ab4f}', "u"), // ꭏ LATIN SMALL LETTER U BAR WITH SHORT RIGHT LEG
    ('\u{ab50}', "ui"), // ꭐ LATIN SMALL LETTER UI
    ('\u{ab51}', "ui"), // ꭑ LATIN SMALL LETTER TURNED UI
    ('\u{ab52}', "u"), // ꭒ LATIN SMALL LETTER U WITH LEFT HOOK
    ('\u{ab53}', "x"), // ꭓ LATIN SMALL LETTER CHI
    ('\u{ab54}', "x"), // ꭔ LATIN SMALL LETTER CHI WITH LOW RIGHT RING
    ('\u{ab55}', "x"), // ꭕ LATIN SMALL LETTER CHI WITH LOW LEFT SERIF
    ('\u{ab56}', "x"), // ꭖ LATIN SMALL LETTER X WITH LOW RIGHT RING
    ('\u{ab57}', "x"), // ꭗ LATIN SMALL LETTER X WITH LONG LEFT LEG
    ('\u{ab58}', "x"), // ꭘ LATIN SMALL LETTER X WITH LONG LEFT LEG AND LOW RIGHT RING
    ('\u{ab59}', "x"), // ꭙ LATIN SMALL LETTER X WITH LONG LEFT LEG WITH SERIF
    ('\u{ab5a}', "y"), // ꭚ LATIN SMALL LETTER Y WITH SHORT RIGHT LEG
    ('\u{ab5c}', "h"), // ꭜ MODIFIER LETTER SMALL HENG
    ('\u{ab5d}', "l"), // ꭝ MODIFIER LETTER SMALL L WITH INVERTED LAZY S
    ('\u{ab5e}', "l"), // ꭞ MODIFIER LETTER SMALL L WITH MIDDLE TILDE
    ('\u{ab5f}', "u"), // ꭟ MODIFIER LETTER SMALL U WITH LEFT HOOK
    ('\u{ab60}', "ia"), // ꭠ LATIN SMALL LETTER SAKHA YAT
    ('\u{ab61}', "ie"), // ꭡ LATIN SMALL LETTER IOTIFIED E
    ('\u{ab62}', "oe"), // ꭢ LATIN SMALL LETTER OPEN OE
    ('\u{ab63}', "uo"), // ꭣ LATIN SMALL LETTER UO
    ('\u{ab64}', "a"), // ꭤ LATIN SMALL LETTER INVERTED ALPHA
    ('\u{ab65}', "o"), // ꭥ GREEK LETTER SMALL CAPITAL OMEGA
    ('\u{ab66}', "dz"), // ꭦ LATIN SMALL LETTER DZ DIGRAPH WITH RETROFLEX HOOK
    ('\u{ab67}', "ts"), // ꭧ LATIN SMALL LETTER TS DIGRAPH WITH RETROFLEX HOOK
    ('\u{ab68}', "r"), // ꭨ LATIN SMALL LETTER TURNED R WITH MIDDLE TILDE
    ('\u{ab69}', "w"), // ꭩ MODIFIER LETTER SMALL TURNED W
];
