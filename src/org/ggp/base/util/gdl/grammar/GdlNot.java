
package org.ggp.base.util.gdl.grammar;

/**
 * A <i>not</i> is a negated <i>literal</i>.
 *
 * See {@link Gdl} for a complete description of the GDL hierarchy.
 */
@SuppressWarnings("serial")
public final class GdlNot extends GdlLiteral
{

  private final GdlLiteral  body;
  private transient Boolean ground;

  GdlNot(GdlLiteral body)
  {
    this.body = body;
    ground = null;
  }

  public GdlLiteral getBody()
  {
    return body;
  }

  @Override
  public boolean isGround()
  {
    if (ground == null)
    {
      ground = body.isGround();
    }

    return ground;
  }

  @Override
  public String toString()
  {
    return "( not " + body + " )";
  }

}
