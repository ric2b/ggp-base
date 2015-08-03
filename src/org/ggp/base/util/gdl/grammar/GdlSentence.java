
package org.ggp.base.util.gdl.grammar;

import java.util.List;

/**
 * A sentence is something that can be true or false on a given turn of a game.  It is made up of a name (which is a
 * <i>constant</i>) and (optionally) a body consisting of <i>terms</i>.  There are two types of sentence depending on
 * whether or not it has a body.
 *
 * See {@link Gdl} for a complete description of the GDL hierarchy.
 */
@SuppressWarnings("serial")
public abstract class GdlSentence extends GdlLiteral
{
  public abstract int arity();

  public abstract GdlTerm get(int index);

  public abstract GdlConstant getName();

  public abstract GdlTerm toTerm();

  public abstract List<GdlTerm> getBody();
}
