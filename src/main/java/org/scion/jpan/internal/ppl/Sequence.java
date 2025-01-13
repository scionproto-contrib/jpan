// Copyright 2024 ETH Zurich
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package org.scion.jpan.internal.ppl;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.regex.Pattern;
import org.antlr.v4.runtime.ANTLRErrorListener;
import org.antlr.v4.runtime.ANTLRInputStream;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.Parser;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.TokenStream;
import org.antlr.v4.runtime.atn.ATNConfigSet;
import org.antlr.v4.runtime.dfa.DFA;
import org.antlr.v4.runtime.tree.ParseTreeListener;
import org.antlr.v4.runtime.tree.ParseTreeWalker;
import org.scion.jpan.*;
import org.scion.jpan.SequenceBaseListener;
import org.scion.jpan.SequenceLexer;
import org.scion.jpan.SequenceParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Sequence {

  private static final Logger LOG = LoggerFactory.getLogger(Sequence.class.getName());

  private static final String isdWildcard = "([0-9]+)";
  private static final String asWildcard = "(([0-9]+)|([0-9a-fA-F]+:[0-9a-fA-F]+:[0-9a-fA-F]+))";
  private static final String ifWildcard = "([0-9]+)";

  private Pattern re;
  private String srcstr;
  private String restr;

  private Sequence(Pattern re, String srcstr, String restr) {
    this.re = re;
    this.srcstr = srcstr;
    this.restr = restr;
  }

  // NewSequence creates a new sequence from a string
  public static Sequence create(String s) throws IOException {
    // fmt.Printf("COMPILE: %s\n", s)
    if ("".equals(s)) {
      return new Sequence(null, null, null);
    }
    ANTLRInputStream istream = new ANTLRInputStream(new StringReader(s));
    SequenceLexer lexer = new SequenceLexer(istream);
    lexer.removeErrorListeners();
    ErrorListener errListener = new ErrorListener();
    lexer.addErrorListener(errListener);
    TokenStream tstream = new CommonTokenStream(lexer, Token.DEFAULT_CHANNEL);
    SequenceParser parser = new SequenceParser(tstream);
    parser.removeErrorListeners();
    parser.addErrorListener(errListener);
    MySequenceListener listener = new MySequenceListener();
    new ParseTreeWalker().walk((ParseTreeListener) listener, parser.start());
    if (errListener.msg != "") {
      throw new PPLException(
          "Failed to parse a sequence: " + "sequence=" + s + " ;msg=" + errListener.msg);
    }
    String restr = String.format("^%s$", listener.stack.get(0));
    Pattern re;
    try {
      re = Pattern.compile(restr);
    } catch (Exception e) {
      // This should never happen. Sequence parser should produce a valid regexp.
      throw new PPLException("Error while parsing sequence regexp: " + "regexp=" + restr);
    }
    return new Sequence(re, s, restr);
  }

  // Eval evaluates the interface sequence list and returns the set of paths that match the list
  List<Path> eval(List<Path> paths) {
    if (this == null || "".equals(srcstr)) {
      return paths;
    }
    List<Path> result = new ArrayList<>();
    for (Path path : paths) {
      String desc;
      try {
        desc = getSequence(path);
        if (!"".equals(desc)) {
          desc = desc + " ";
        }
      } catch (Exception e) {
        LOG.error("get sequence from path", e);
        continue;
      }
      // Check whether the string matches the sequence regexp.
      if (re.matcher(desc).matches()) {
        result.add(path);
      }
    }
    return result;
  }

  private String string() {
    return srcstr;
  }

  //    JsonWriter MarshalJSON(JsonWriter json) {
  //        return json.Marshal(s.srcstr);
  //    }
  //
  //    JsonReader UnmarshalJSON(JsonReader json) {
  //        String string;
  //        json.Unmarshal(b, str);
  //        Sequence sn = newSequence(str);
  //    	this = sn;
  //        return json;
  //    }

  //    func (s *Sequence) MarshalYAML() (interface{}, error) {
  //        return s.srcstr, nil
  //    }
  //
  //    func (s *Sequence) UnmarshalYAML(unmarshal func(interface{}) error) error {
  //        var str string
  //        err := unmarshal(&str)
  //        if err != nil {
  //            return err
  //        }
  //        sn, err := NewSequence(str)
  //        if err != nil {
  //            return err
  //        }
  //	*s = *sn
  //        return nil
  //    }

  private static class ErrorListener implements ANTLRErrorListener {
    // *antlr.DefaultErrorListener
    String msg;

    @Override
    public void syntaxError(
        Recognizer<?, ?> recognizer,
        Object o,
        int line,
        int column,
        String msg,
        RecognitionException e) {
      this.msg += String.format("%d:%d %s\n", line, column, msg);
    }

    @Override
    public void reportAmbiguity(
        Parser parser,
        DFA dfa,
        int line,
        int column,
        boolean b,
        BitSet bitSet,
        ATNConfigSet atnConfigSet) {
      throw new UnsupportedOperationException();
      // this.msg += String.format("%d:%d %s\n", line, column, msg);
    }

    @Override
    public void reportAttemptingFullContext(
        Parser parser, DFA dfa, int i, int i1, BitSet bitSet, ATNConfigSet atnConfigSet) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void reportContextSensitivity(
        Parser parser, DFA dfa, int i, int i1, int i2, ATNConfigSet atnConfigSet) {
      throw new UnsupportedOperationException();
    }
  }

  private static class MySequenceListener extends SequenceBaseListener {
    List<String> stack = new ArrayList<>();

    void push(String s) {
      stack.add(s);
    }

    String pop() {
      String result;
      if (stack.isEmpty()) {
        // X is used as a substitute for the token during recovery from parsing errors.
        result = "X";
      } else {
        result = stack.removeLast();
      }
      return result;
    }

    public void exitStart(SequenceParser.StartContext c) {
      String re = pop();
      // fmt.Printf("Start: %s RE: %s\n", c.GetText(), re)
      push(re);
    }

    public void exitQuestionMark(SequenceParser.QuestionMarkContext c) {
      String re = String.format("(%s)?", pop());
      // fmt.Printf("QuestionMark: %s RE: %s\n", c.GetText(), re)
      push(re);
    }

    public void exitPlus(SequenceParser.PlusContext c) {
      String re = String.format("(%s)+", pop());
      // fmt.Printf("Plus: %s RE: %s\n", c.GetText(), re)
      push(re);
    }

    public void exitAsterisk(SequenceParser.AsteriskContext c) {
      String re = String.format("(%s)*", pop());
      // fmt.Printf("Asterisk: %s RE: %s\n", c.GetText(), re)
      push(re);
    }

    public void exitOr(SequenceParser.OrContext c) {
      String right = pop();
      String left = pop();
      String re = String.format("(%s|%s)", left, right);
      // fmt.Printf("Or: %s RE: %s\n", c.GetText(), re)
      push(re);
    }

    public void exitConcatenation(SequenceParser.ConcatenationContext c) {
      String right = pop();
      String left = pop();
      String re = String.format("(%s%s)", left, right);
      // fmt.Printf("Concatenation: %s RE: %s\n", c.GetText(), re)
      push(re);
    }

    public void exitParentheses(SequenceParser.ParenthesesContext c) {
      String re = pop();
      // fmt.Printf("Parentheses: %s RE: %s\n", c.GetText(), re)
      push(re);
    }

    public void exitHop(SequenceParser.HopContext c) {
      String re = String.format("(%s +)", pop());
      // fmt.Printf("Hop: %s RE: %s\n", c.GetText(), re)
      push(re);
    }

    public void exitISDHop(SequenceParser.ISDHopContext c) {
      String isd = pop();
      String re = String.format("(%s-%s#%s,%s)", isd, asWildcard, ifWildcard, ifWildcard);
      // fmt.Printf("ISDHop: %s RE: %s\n", c.GetText(), re)
      push(re);
    }

    public void exitISDASHop(SequenceParser.ISDASHopContext c) {
      String as = pop();
      String isd = pop();
      String re = String.format("(%s-%s#%s,%s)", isd, as, ifWildcard, ifWildcard);
      // fmt.Printf("ISDASHop: %s RE: %s\n", c.GetText(), re)
      push(re);
    }

    public void exitISDASIFHop(SequenceParser.ISDASIFHopContext c) {
      String iface = pop();
      String as = pop();
      String isd = pop();
      String re =
          String.format("(%s-%s#((%s,%s)|(%s,%s)))", isd, as, ifWildcard, iface, iface, ifWildcard);
      // fmt.Printf("ISDASIFHop: %s RE: %s\n", c.GetText(), re)
      push(re);
    }

    public void exitISDASIFIFHop(SequenceParser.ISDASIFIFHopContext c) {
      String ifout = pop();
      String ifin = pop();
      String as = pop();
      String isd = pop();
      String re = String.format("(%s-%s#%s,%s)", isd, as, ifin, ifout);
      // fmt.Printf("ISDASIFIFHop: %s RE: %s\n", c.GetText(), re)
      push(re);
    }

    public void exitWildcardISD(SequenceParser.WildcardISDContext c) {
      String re = isdWildcard;
      // fmt.Printf("WildcardISD: %s RE: %s\n", c.GetText(), re)
      push(re);
    }

    public void exitISD(SequenceParser.ISDContext c) {
      String re = c.getText();
      // fmt.Printf("ISD: %s RE: %s\n", c.GetText(), re)
      push(re);
    }

    public void exitWildcardAS(SequenceParser.WildcardASContext c) {
      String re = asWildcard;
      // fmt.Printf("WildcardAS: %s RE: %s\n", c.GetText(), re)
      push(re);
    }

    public void exitLegacyAS(SequenceParser.LegacyASContext c) {
      String re = c.getText().substring(1);
      // fmt.Printf("LegacyAS: %s RE: %s\n", c.GetText(), re)
      push(re);
    }

    public void exitAS(SequenceParser.ASContext c) {
      String re = c.getText().substring(1);
      // fmt.Printf("AS: %s RE: %s\n", c.GetText(), re)
      push(re);
    }

    public void exitWildcardIFace(SequenceParser.WildcardIFaceContext c) {
      String re = ifWildcard;
      // fmt.Printf("WildcardIFace: %s RE: %s\n", c.GetText(), re)
      push(re);
    }

    public void exitIFace(SequenceParser.IFaceContext c) {
      String re = c.getText();
      // fmt.Printf("IFace: %s RE: %s\n", c.GetText(), re)
      push(re);
    }
  }

  private static String hop(long isdAs, long ingress, long egress) {
    return String.format("%s#%d,%d", ScionUtil.toStringIA(isdAs), ingress, egress);
  }

  // GetSequence constructs the sequence string from snet path
  // output format:
  //
  //	1-ff00:0:133#42 1-ff00:0:120#2,1 1-ff00:0:110#21
  public static String getSequence(Path path) {
    List<PathMetadata.PathInterface> ifaces = path.getMetadata().getInterfacesList();
    if (ifaces.size() % 2 != 0) {
      // Path should contain even number of interfaces. 1 for source AS,
      // 1 for destination AS and 2 per each intermediate AS. Invalid paths should
      // not occur but if they do let's ignore them.
      throw new PPLException("Invalid path with odd number of hops: " + "path=" + path);
    }

    if (ifaces.isEmpty()) {
      // Empty paths are special cased.
      return "";
    }

    // Turn the path into a string. For each AS on the path there will be
    // one element in form <IA>#<inbound-interface>,<outbound-interface>,
    // e.g. 64-ff00:0:112#3,5. For the source AS, the inbound interface will be
    // zero. For destination AS, outbound interface will be zero.
    StringBuilder hops = new StringBuilder();
    // hops := make([]string, 0, len(ifaces)/2+1);
    hops.append(hop(ifaces.get(0).getIsdAs(), 0, ifaces.get(0).getId()));
    hops.append(" ");
    for (int i = 1; i < ifaces.size() - 1; i += 2) {
      hops.append(hop(ifaces.get(i).getIsdAs(), ifaces.get(i).getId(), ifaces.get(i + 1).getId()));
      hops.append(" ");
    }
    hops.append(
        hop(ifaces.get(ifaces.size() - 1).getIsdAs(), ifaces.get(ifaces.size() - 1).getId(), 0));
    return hops.toString();
  }
}
