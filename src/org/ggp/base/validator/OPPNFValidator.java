
package org.ggp.base.validator;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

import org.apache.logging.log4j.ThreadContext;
import org.ggp.base.util.game.Game;
import org.ggp.base.util.propnet.polymorphic.factory.OptimizingPolymorphicPropNetFactory;
import org.ggp.base.util.propnet.polymorphic.forwardDeadReckon.ForwardDeadReckonComponentFactory;

/**
 * Game validator that simply checks that a game description can be loaded with our
 * {@link OptimizingPolymorphicPropNetFactory}.
 */
public final class OPPNFValidator implements GameValidator
{
  @Override
  public void checkValidity(Game theGame) throws ValidatorException
  {
    ThreadContext.put("matchID", "Validator." + theGame.getName());

    PrintStream stdout = System.out;
    System.setOut(new PrintStream(new ByteArrayOutputStream()));
    try
    {
      if (OptimizingPolymorphicPropNetFactory.create(theGame.getRules(),
                                                     new ForwardDeadReckonComponentFactory()) == null)
      {
        throw new ValidatorException("Got null result from OPPNF");
      }
    }
    catch (Exception e)
    {
      throw new ValidatorException("OPPNF Exception: " + e, e);
    }
    finally
    {
      System.setOut(stdout);
    }
  }
}
