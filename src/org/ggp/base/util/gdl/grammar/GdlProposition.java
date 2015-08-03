
package org.ggp.base.util.gdl.grammar;

import java.util.Collections;
import java.util.List;

/**
 * A <i>proposition</i> is a <i>sentence</i> with no body.  (Note that this is different from the sense of "proposition"
 * used in propositional network, which is equivalent to a <i>sentence</i> in GDL terminology.)
 *
 * See {@link Gdl} for a complete description of the GDL hierarchy.
 */
@SuppressWarnings("serial")
public final class GdlProposition extends GdlSentence
{
  private final GdlConstant name;

  protected GdlProposition(GdlConstant name)
  {
    this.name = name;
  }

  @Override
  public int arity()
  {
    return 0;
  }

  @Override
  public GdlTerm get(int index)
  {
    throw new RuntimeException("GdlPropositions have no body!");
  }

  @Override
  public GdlConstant getName()
  {
    return name;
  }

  @Override
  public boolean isGround()
  {
    return name.isGround();
  }

  @Override
  public String toString()
  {
    return name.toString();
  }

  @Override
  public GdlTerm toTerm()
  {
    return name;
  }

  @Override
  public List<GdlTerm> getBody()
  {
    return Collections.emptyList();
  }

}
