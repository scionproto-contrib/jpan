grammar Sequence;

fragment HEXA: [1-9a-fA-F][0-9a-fA-F]* | '0';

WHITESPACE: [ \t\r\n]+ -> skip;
ZERO: '0';
NUM: [1-9][0-9]*;
WILDCARDAS: '-' '0';
LEGACYAS: '-' NUM;
ASCODE: '-' HEXA ':' HEXA ':' HEXA;
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
    : isdcode                            # ISDHop
    | isdcode ascode                         # ISDASHop
    | isdcode ascode HASH ifaceid              # ISDASIFHop
    | isdcode ascode HASH ifaceid COMMA ifaceid  # ISDASIFIFHop
    ;

isdcode
    : ZERO # WildcardISD
    | NUM  # ISD
    ;

ascode
    : WILDCARDAS # WildcardAS
    | LEGACYAS   # LegacyAS
    | ASCODE        # AS
    ;

ifaceid
    : ZERO # WildcardIFace
    | NUM  # IFace
    ;
