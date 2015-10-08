package org.ggp.base.util.propnet.polymorphic.tristate;

import org.ggp.base.util.propnet.polymorphic.PolymorphicTransition;

public class TristateTransition extends TristateComponent implements PolymorphicTransition
{
  public TristateTransition(TristatePropNet xiNetwork)
  {
    super(xiNetwork);
  }

  @Override
  public void changeInput(Tristate xiNewValue, int xiTurn)
  {
    // Only propagate through a transition if we haven't done so already.  We're only interested in the immediate
    // consequences of assuming some input proposition(s).
    if ((mState[xiTurn].mValue == Tristate.UNKNOWN) && (xiTurn < 2))
    {
      mState[xiTurn].mValue = xiNewValue;
      changeOutput(xiTurn, true);
    }
  }
}
