
package org.ggp.base.util.gdl.grammar;

/**
 * A <i>variable</i> is a "word" in a sentence starting with ?. It can represent a <i>constant</i> or a <i>function</i>.
 *
 * See {@link Gdl} for a complete description of the GDL hierarchy.
 */
@SuppressWarnings("serial")
public final class GdlVariable extends GdlTerm
{

  private final String name;

  GdlVariable(String name)
  {
    this.name = name.intern();
  }

  public String getName()
  {
    return name;
  }

  @Override
  public boolean isGround()
  {
    return false;
  }

  @Override
  public GdlSentence toSentence()
  {
    throw new RuntimeException("Unable to convert a GdlVariable to a GdlSentence!");
  }

  @Override
  public String toString()
  {
    return name;
  }

}
