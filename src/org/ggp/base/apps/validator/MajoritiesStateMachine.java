// C4StateMachine.java
// (C) COPYRIGHT METASWITCH NETWORKS 2015
package org.ggp.base.apps.validator;

import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;

import org.ggp.base.util.gdl.grammar.Gdl;
import org.ggp.base.util.gdl.grammar.GdlPool;
import org.ggp.base.util.gdl.grammar.GdlTerm;
import org.ggp.base.util.statemachine.MachineState;
import org.ggp.base.util.statemachine.Move;
import org.ggp.base.util.statemachine.Role;
import org.ggp.base.util.statemachine.StateMachine;

/**
 * Majorities specific state machine.
 */
public class MajoritiesStateMachine extends StateMachine
{
  public static final int NUM_HEXES = 55;

  public Role[]                 mRoles = new Role[2];
  public Move                   mNoop;
  public List<Move>             mNoopList;
  public MajoritiesMove[]       mPlaces = new MajoritiesMove[NUM_HEXES];
  public MajoritiesMachineState mState = new MajoritiesMachineState();

  private int[] sReqCount = new int[] {3, 3, 4, 4, 4, 4, 4, 3, 3};

  @Override
  public void initialize(List<Gdl> xiDescription)
  {
    // Generate the roles.
    mRoles[0] = new Role(GdlPool.getConstant("red"));
    mRoles[1] = new Role(GdlPool.getConstant("blue"));

    // Generate the no-op move.
    mNoop = new Move(GdlPool.getConstant("noop"));
    mNoopList = Arrays.asList(new Move[]{mNoop});

    int lHex = 0;
    mPlaces[lHex] = new MajoritiesMove(lHex, "a5", 0, 4, 0); lHex++;
    mPlaces[lHex] = new MajoritiesMove(lHex, "a6", 0, 5, 1); lHex++;
    mPlaces[lHex] = new MajoritiesMove(lHex, "a7", 0, 6, 2); lHex++;
    mPlaces[lHex] = new MajoritiesMove(lHex, "a8", 0, 7, 3); lHex++;
    mPlaces[lHex] = new MajoritiesMove(lHex, "a9", 0, 8, 4); lHex++;
    mPlaces[lHex] = new MajoritiesMove(lHex, "b4", 1, 3, 0); lHex++;
    mPlaces[lHex] = new MajoritiesMove(lHex, "b5", 1, 4, 1); lHex++;
    mPlaces[lHex] = new MajoritiesMove(lHex, "b6", 1, 5, 2); lHex++;
    mPlaces[lHex] = new MajoritiesMove(lHex, "b7", 1, 6, 3); lHex++;
    mPlaces[lHex] = new MajoritiesMove(lHex, "b9", 1, 8, 5); lHex++;
    mPlaces[lHex] = new MajoritiesMove(lHex, "c3", 2, 2, 0); lHex++;
    mPlaces[lHex] = new MajoritiesMove(lHex, "c4", 2, 3, 1); lHex++;
    mPlaces[lHex] = new MajoritiesMove(lHex, "c5", 2, 4, 2); lHex++;
    mPlaces[lHex] = new MajoritiesMove(lHex, "c6", 2, 5, 3); lHex++;
    mPlaces[lHex] = new MajoritiesMove(lHex, "c7", 2, 6, 4); lHex++;
    mPlaces[lHex] = new MajoritiesMove(lHex, "c8", 2, 7, 5); lHex++;
    mPlaces[lHex] = new MajoritiesMove(lHex, "c9", 2, 8, 6); lHex++;
    mPlaces[lHex] = new MajoritiesMove(lHex, "d2", 3, 1, 0); lHex++;
    mPlaces[lHex] = new MajoritiesMove(lHex, "d3", 3, 2, 1); lHex++;
    mPlaces[lHex] = new MajoritiesMove(lHex, "d4", 3, 3, 2); lHex++;
    mPlaces[lHex] = new MajoritiesMove(lHex, "d5", 3, 4, 3); lHex++;
    mPlaces[lHex] = new MajoritiesMove(lHex, "d7", 3, 6, 5); lHex++;
    mPlaces[lHex] = new MajoritiesMove(lHex, "d8", 3, 7, 6); lHex++;
    mPlaces[lHex] = new MajoritiesMove(lHex, "d9", 3, 8, 7); lHex++;
    mPlaces[lHex] = new MajoritiesMove(lHex, "e1", 4, 0, 0); lHex++;
    mPlaces[lHex] = new MajoritiesMove(lHex, "e3", 4, 2, 2); lHex++;
    mPlaces[lHex] = new MajoritiesMove(lHex, "e5", 4, 4, 4); lHex++;
    mPlaces[lHex] = new MajoritiesMove(lHex, "e6", 4, 5, 5); lHex++;
    mPlaces[lHex] = new MajoritiesMove(lHex, "e7", 4, 6, 6); lHex++;
    mPlaces[lHex] = new MajoritiesMove(lHex, "e8", 4, 7, 7); lHex++;
    mPlaces[lHex] = new MajoritiesMove(lHex, "e9", 4, 8, 8); lHex++;
    mPlaces[lHex] = new MajoritiesMove(lHex, "f1", 5, 0, 1); lHex++;
    mPlaces[lHex] = new MajoritiesMove(lHex, "f2", 5, 1, 2); lHex++;
    mPlaces[lHex] = new MajoritiesMove(lHex, "f3", 5, 2, 3); lHex++;
    mPlaces[lHex] = new MajoritiesMove(lHex, "f4", 5, 3, 4); lHex++;
    mPlaces[lHex] = new MajoritiesMove(lHex, "f6", 5, 5, 6); lHex++;
    mPlaces[lHex] = new MajoritiesMove(lHex, "f7", 5, 6, 7); lHex++;
    mPlaces[lHex] = new MajoritiesMove(lHex, "f8", 5, 7, 8); lHex++;
    mPlaces[lHex] = new MajoritiesMove(lHex, "g1", 6, 0, 2); lHex++;
    mPlaces[lHex] = new MajoritiesMove(lHex, "g2", 6, 1, 3); lHex++;
    mPlaces[lHex] = new MajoritiesMove(lHex, "g3", 6, 2, 4); lHex++;
    mPlaces[lHex] = new MajoritiesMove(lHex, "g4", 6, 3, 5); lHex++;
    mPlaces[lHex] = new MajoritiesMove(lHex, "g5", 6, 4, 6); lHex++;
    mPlaces[lHex] = new MajoritiesMove(lHex, "g6", 6, 5, 7); lHex++;
    mPlaces[lHex] = new MajoritiesMove(lHex, "g7", 6, 6, 8); lHex++;
    mPlaces[lHex] = new MajoritiesMove(lHex, "h1", 7, 0, 3); lHex++;
    mPlaces[lHex] = new MajoritiesMove(lHex, "h2", 7, 1, 4); lHex++;
    mPlaces[lHex] = new MajoritiesMove(lHex, "h3", 7, 2, 5); lHex++;
    mPlaces[lHex] = new MajoritiesMove(lHex, "h4", 7, 3, 6); lHex++;
    mPlaces[lHex] = new MajoritiesMove(lHex, "h6", 7, 5, 8); lHex++;
    mPlaces[lHex] = new MajoritiesMove(lHex, "i1", 8, 0, 4); lHex++;
    mPlaces[lHex] = new MajoritiesMove(lHex, "i2", 8, 1, 5); lHex++;
    mPlaces[lHex] = new MajoritiesMove(lHex, "i3", 8, 2, 6); lHex++;
    mPlaces[lHex] = new MajoritiesMove(lHex, "i4", 8, 3, 7); lHex++;
    mPlaces[lHex] = new MajoritiesMove(lHex, "i5", 8, 4, 8); lHex++;
  }

  @Override
  public Role[] getRoles()
  {
    return mRoles;
  }

  @Override
  public MachineState getInitialState()
  {
    return new MajoritiesMachineState();
    // return mState.reset();
  }

  @Override
  public boolean isTerminal(MachineState xiState)
  {
    return ((MajoritiesMachineState)xiState).mTerminal;
  }

  @Override
  public List<Move> getLegalMoves(MachineState xiState, Role xiRole)
  {
    MajoritiesMachineState lState = (MajoritiesMachineState)xiState;

    if (( lState.mRedToPlay && (xiRole == mRoles[0])) ||
        (!lState.mRedToPlay && (xiRole == mRoles[1])))
    {
      return lState.mAvailableMoves;
    }

    return mNoopList;
  }

  @Override
  public MachineState getNextState(MachineState xiState, List<Move> xiMoves)
  {
    MajoritiesMachineState lState = (MajoritiesMachineState)xiState;

    // The cell has been played in.
    int lPlayerIndex = (lState.mRedToPlay) ? 0 : 1;
    MajoritiesMove lMove = (MajoritiesMove)(xiMoves.get(lPlayerIndex));

    lState.mAvailableMoves.remove(lMove);
    if (++lState.mD0Count[lPlayerIndex][lMove.mD0Index] == sReqCount[lMove.mD0Index])
    {
      if (++lState.mD0Lines[lPlayerIndex] == 5)
      {
        if (++lState.mDirections[lPlayerIndex] == 2)
        {
          lState.mTerminal = true;
        }
      }
    }

    if (++lState.mD1Count[lPlayerIndex][lMove.mD1Index] == sReqCount[lMove.mD1Index])
    {
      if (++lState.mD1Lines[lPlayerIndex] == 5)
      {
        if (++lState.mDirections[lPlayerIndex] == 2)
        {
          lState.mTerminal = true;
        }
      }
    }

    if (++lState.mD2Count[lPlayerIndex][lMove.mD2Index] == sReqCount[lMove.mD2Index])
    {
      if (++lState.mD2Lines[lPlayerIndex] == 5)
      {
        if (++lState.mDirections[lPlayerIndex] == 2)
        {
          lState.mTerminal = true;
        }
      }
    }

    // Work out who's turn it is next.  (2 turns per player after the first turn.)
    boolean lRedJustPlayed = (lPlayerIndex == 0);
    lState.mRedToPlay = !lState.mRedPlayedLast;
    lState.mRedPlayedLast = lRedJustPlayed;

    return lState;
  }

  @Override
  public int getGoal(MachineState xiState, Role xiRole)
  {
    return 50;
  }

  public class MajoritiesMachineState extends MachineState
  {
    public boolean mRedToPlay = true;
    public boolean mRedPlayedLast = true;
    public boolean mTerminal = false;
    public final List<Move> mAvailableMoves = new LinkedList<>();
    public final int[][] mD0Count = new int[2][9];
    public final int[][] mD1Count = new int[2][9];
    public final int[][] mD2Count = new int[2][9];
    public final int[] mD0Lines = new int[2];
    public final int[] mD1Lines = new int[2];
    public final int[] mD2Lines = new int[2];
    public final int[] mDirections = new int[2];

    public MajoritiesMachineState()
    {
      reset();
    }

    public MajoritiesMachineState reset()
    {
      mRedToPlay = true;
      mTerminal = false;

      mAvailableMoves.clear();
      for (int lii = 0; lii < MajoritiesStateMachine.NUM_HEXES; lii++)
      {
        mAvailableMoves.add(mPlaces[lii]);
      }

      for (int lii = 0; lii < 2; lii++)
      {
        mD0Lines[lii] = 0;
        mD1Lines[lii] = 0;
        mD2Lines[lii] = 0;
        mDirections[lii] = 0;
        for (int ljj = 0; ljj < 9; ljj++)
        {
          mD0Count[lii][ljj] = 0;
          mD1Count[lii][ljj] = 0;
          mD2Count[lii][ljj] = 0;
        }
      }

      return this;
    }
  }

  public static class MajoritiesMove extends Move
  {
    private static final long serialVersionUID = 1L;

    public final int mIndex;
    public final int mD0Index;
    public final int mD1Index;
    public final int mD2Index;

    public MajoritiesMove(int xiIndex,
                          String xiName,
                          int xiD0Index,
                          int xiD1Index,
                          int xiD2Index)
    {
      super(GdlPool.getFunction(GdlPool.getConstant("place"),
            new GdlTerm[] {GdlPool.getConstant("" + xiD0Index), GdlPool.getConstant("" + xiD1Index)}));
      mIndex = xiIndex;
      mD0Index = xiD0Index;
      mD1Index = xiD1Index;
      mD2Index = xiD2Index;
    }
  }
}