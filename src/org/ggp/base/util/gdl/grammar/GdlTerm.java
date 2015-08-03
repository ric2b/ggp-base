
package org.ggp.base.util.gdl.grammar;

/**
 * A term is a <i>constant</i>, <i>variable</i>, or <i>function</i>.  It's what fills one "slot" in the body of a
 * <i>sentence</i>.
 *
 * See {@link Gdl} for a complete description of the GDL hierarchy.
 */
@SuppressWarnings("serial")
public abstract class GdlTerm extends Gdl
{
  /**
   * @return the sentence form of this GdlTerm.  (When converted to sentence form, a GdlConstant becomes a
   *         GdlProposition and a GdlFunction becomes a GdlRelation.  A GdlVariable cannot be converted to sentence
   *         form.)
   */
  public abstract GdlSentence toSentence();
}
