
package org.ggp.base.util.gdl.grammar;

/**
 * A <i>constant</i> is a "word" of the language for a particular game, or one of the GDL keywords.  If it's a single
 * word without parentheses (and not a variable) it's a <i>constant</i>.
 *
 * See {@link Gdl} for a complete description of the GDL hierarchy.
 */
@SuppressWarnings("serial")
public final class GdlConstant extends GdlTerm
{

  private final String value;

  GdlConstant(String value)
  {
    this.value = value.intern();
  }

  public String getValue()
  {
    return value;
  }

  @Override
  public boolean isGround()
  {
    return true;
  }

  @Override
  public GdlProposition toSentence()
  {
    return GdlPool.getProposition(this);
  }

  @Override
  public String toString()
  {
    return value;
  }

}
