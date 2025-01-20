grammar Sequence;

fragment HEXA: [1-9a-fA-F][0-9a-fA-F]* | '0';

WHITESPACE: [ \t\r\n]+ -> skip;
ZERO: '0';
NUM: [1-9][0-9]*;
WILDCARDAS: '-' '0';
LEGACYAS: '-' NUM;
ASX: '-' HEXA ':' HEXA ':' HEXA;
HASH: '#';
COMMA: ',';
QUESTIONMARK: '?';
PLUS: '+';
ASTERISK: '*';
OR: '|';
LPAR: '(';
RPAR: ')';

start: sequence EOF;

sequence
    : sequence QUESTIONMARK # QuestionMark
    | sequence PLUS         # Plus
    | sequence ASTERISK     # Asterisk
    | sequence OR sequence  # Or
    | sequence sequence     # Concatenation
    | LPAR sequence RPAR    # Parentheses
    | onehop                # Hop
    ;

onehop
    : isdx                            # ISDHop
    | isdx asx                         # ISDASHop
    | isdx asx HASH intface              # ISDASIFHop
    | isdx asx HASH intface COMMA intface  # ISDASIFIFHop
    ;

isdx
    : ZERO # WildcardISD
    | NUM  # ISD
    ;

asx
    : WILDCARDAS # WildcardAS
    | LEGACYAS   # LegacyAS
    | ASX        # AS
    ;

intface
    : ZERO # WildcardIFace
    | NUM  # IFace
    ;
