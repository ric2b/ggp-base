
package org.ggp.base.util.prover;

import java.util.Set;

import org.ggp.base.util.gdl.grammar.GdlSentence;

public interface Prover
{
  public Set<GdlSentence> askAll(GdlSentence query, Set<GdlSentence> context);
  public GdlSentence askOne(GdlSentence query, Set<GdlSentence> context);
  public boolean prove(GdlSentence query, Set<GdlSentence> context);
}
