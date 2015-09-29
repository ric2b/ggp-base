package org.ggp.base.util.propnet.polymorphic.tristate;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.ggp.base.util.gdl.grammar.GdlSentence;
import org.ggp.base.util.propnet.polymorphic.PolymorphicProposition;

public class TristateProposition extends TristateComponent implements PolymorphicProposition
{
  private static final Logger LOGGER = LogManager.getLogger();

  private GdlSentence mName;

  @Override
  public GdlSentence getName()
  {
    return mName;
  }

  @Override
  public void setName(GdlSentence xiNewName)
  {
    mName = xiNewName;
  }

  @Override
  public void setValue(boolean xiValue)
  {
    if (mValue != Tristate.UNKNOWN)
    {
      mValue = (xiValue ? Tristate.TRUE : Tristate.FALSE);
      changeOutput();
    }
  }

  @Override
  public void changeInput(Tristate xiNewValue)
  {
    if (mValue != Tristate.UNKNOWN)
    {
      mValue = xiNewValue;

      if (mValue == Tristate.TRUE)
      {
        LOGGER.info(getName() + " must be true");
      }
      else
      {
        LOGGER.info(getName() + " must be false");
      }
      changeOutput();
    }
  }
}
