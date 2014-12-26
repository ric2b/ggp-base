
package org.ggp.base.util.gdl.grammar;

import java.util.List;

/**
 * A <i>function</i> is a complex <i>term</i> that contains other <i>terms</i>.  It has a name (which is a
 * <i>constant</i>) and a body consisting of other <i>terms</i>, much like a <i>sentence</i>.  Unlike a <i>sentence</i>,
 * however, it does not have a truth value.
 *
 * See {@link Gdl} for a complete description of the GDL hierarchy.
 */
@SuppressWarnings("serial")
public final class GdlFunction extends GdlTerm
{

  private final List<GdlTerm> body;
  private transient Boolean   ground;
  private final GdlConstant   name;

  GdlFunction(GdlConstant name, List<GdlTerm> body)
  {
    this.name = name;
    this.body = body;
    ground = null;
  }

  public int arity()
  {
    return body.size();
  }

  private boolean computeGround()
  {
    for (GdlTerm term : body)
    {
      if (!term.isGround())
      {
        return false;
      }
    }

    return true;
  }

  public GdlTerm get(int index)
  {
    return body.get(index);
  }

  public GdlConstant getName()
  {
    return name;
  }

  public List<GdlTerm> getBody()
  {
    return body;
  }

  @Override
  public boolean isGround()
  {
    if (ground == null)
    {
      ground = computeGround();
    }

    return ground;
  }

  @Override
  public GdlRelation toSentence()
  {
    return GdlPool.getRelation(name, body);
  }

  @Override
  public String toString()
  {
    StringBuilder sb = new StringBuilder();

    sb.append("( " + name + " ");
    for (GdlTerm term : body)
    {
      sb.append(term + " ");
    }
    sb.append(")");

    return sb.toString();
  }

}
