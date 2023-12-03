package translation;


import minillvm.ast.*;
import notquitejava.ast.*;
import static minillvm.ast.Ast.*;


/**
 * Evaluate r values.
 */
public class ExprRValue implements NQJExpr.Matcher<Operand> {
  private final Translator tr;

  public ExprRValue(Translator translator) {
    this.tr = translator;
  }

  @Override
  public Operand case_ExprUnary(NQJExprUnary e) {
    Operand expr = tr.exprRvalue(e.getExpr());

    return e.getUnaryOperator().match(new NQJUnaryOperator.Matcher<>() {

      @Override
      public Operand case_UnaryMinus(NQJUnaryMinus unaryMinus) {
        TemporaryVar v = TemporaryVar("minus_res");
        tr.addInstruction(BinaryOperation(v, ConstInt(0), Ast.Sub(), expr));
        return VarRef(v);
      }

      @Override
      public Operand case_Negate(NQJNegate negate) {
        TemporaryVar v = TemporaryVar("neg_res");
        tr.addInstruction(BinaryOperation(v, Ast.ConstBool(false), Eq(), expr));
        return VarRef(v);
      }
    });
  }

  @Override
  public Operand case_ArrayLength(NQJArrayLength e) {
    Operand a = tr.exprRvalue(e.getArrayExpr());
    tr.addNullcheck(a,
        "Nullpointer exception when reading array length in line " + tr.sourceLine(e));
    return tr.getArrayLen(a);
  }

  @Override
  public Operand case_NewArray(NQJNewArray newArray) {
    Type componentType = tr.translateType(newArray.getBaseType());
    Operand arraySize = tr.exprRvalue(newArray.getArraySize());
    Operand proc = tr.getNewArrayFunc(componentType);
    TemporaryVar res = TemporaryVar("newArray");
    tr.addInstruction(Ast.Call(res, proc, OperandList(arraySize)));
    return VarRef(res);
  }

  @Override
  public Operand case_ExprBinary(NQJExprBinary e) {
    Operand left = tr.exprRvalue(e.getLeft());
    return e.getOperator().match(new NQJOperator.Matcher<>() {
      @Override
      public Operand case_And(NQJAnd and) {
        BasicBlock andRight = tr.newBasicBlock("and_first_true");
        BasicBlock andEnd = tr.newBasicBlock("and_end");
        TemporaryVar andResVar = TemporaryVar("andResVar");
        tr.getCurrentBlock().add(Ast.Alloca(andResVar, Ast.TypeBool()));
        tr.getCurrentBlock().add(Ast.Store(VarRef(andResVar), left));
        tr.getCurrentBlock().add(Ast.Branch(left.copy(), andRight, andEnd));

        tr.addBasicBlock(andRight);
        tr.setCurrentBlock(andRight);
        Operand right = tr.exprRvalue(e.getRight());
        tr.getCurrentBlock().add(Ast.Store(VarRef(andResVar), right));
        tr.getCurrentBlock().add(Ast.Jump(andEnd));

        tr.addBasicBlock(andEnd);
        tr.setCurrentBlock(andEnd);
        TemporaryVar andRes = TemporaryVar("andRes");
        andEnd.add(Ast.Load(andRes, VarRef(andResVar)));
        return VarRef(andRes);
      }


      private Operand normalCase(Operator op) {
        Operand right = tr.exprRvalue(e.getRight());
        TemporaryVar result = TemporaryVar("res" + op.getClass().getSimpleName());
        tr.addInstruction(BinaryOperation(result, left, op, right));
        return VarRef(result);
      }

      @Override
      public Operand case_Times(NQJTimes times) {
        return normalCase(Ast.Mul());
      }


      @Override
      public Operand case_Div(NQJDiv div) {
        Operand right = tr.exprRvalue(e.getRight());
        TemporaryVar divResVar = TemporaryVar("divResVar");
        tr.addInstruction(Ast.Alloca(divResVar, Ast.TypeInt()));
        TemporaryVar isZero = TemporaryVar("isZero");
        tr.addInstruction(BinaryOperation(isZero, right, Eq(), ConstInt(0)));
        BasicBlock ifZero = tr.newBasicBlock("ifZero");
        BasicBlock notZero = tr.newBasicBlock("notZero");

        tr.addInstruction(Ast.Branch(VarRef(isZero), ifZero, notZero));

        tr.addBasicBlock(ifZero);
        ifZero.add(Ast.HaltWithError("Division by zero in line " + tr.sourceLine(e)));


        tr.addBasicBlock(notZero);
        tr.setCurrentBlock(notZero);

        BasicBlock divEnd = tr.newBasicBlock("div_end");
        BasicBlock divNoOverflow = tr.newBasicBlock("div_noOverflow");

        TemporaryVar isMinusOne = TemporaryVar("isMinusOne");
        tr.addInstruction(BinaryOperation(isMinusOne,
            right.copy(), Eq(), ConstInt(-1)));
        TemporaryVar isMinInt = TemporaryVar("isMinInt");
        tr.addInstruction(BinaryOperation(isMinInt,
            left.copy(), Eq(), ConstInt(Integer.MIN_VALUE)));
        TemporaryVar isOverflow = TemporaryVar("isOverflow");
        tr.addInstruction(BinaryOperation(isOverflow,
            VarRef(isMinInt), And(), VarRef(isMinusOne)));
        tr.addInstruction(Ast.Store(VarRef(divResVar), ConstInt(Integer.MIN_VALUE)));
        tr.addInstruction(Ast.Branch(VarRef(isOverflow), divEnd, divNoOverflow));


        tr.addBasicBlock(divNoOverflow);
        tr.setCurrentBlock(divNoOverflow);
        TemporaryVar divResultA = TemporaryVar("divResultA");
        tr.addInstruction(BinaryOperation(divResultA, left, Ast.Sdiv(), right.copy()));
        tr.addInstruction(Ast.Store(VarRef(divResVar), VarRef(divResultA)));
        tr.addInstruction(Ast.Jump(divEnd));


        tr.addBasicBlock(divEnd);
        tr.setCurrentBlock(divEnd);
        TemporaryVar divResultB = TemporaryVar("divResultB");
        tr.addInstruction(Ast.Load(divResultB, VarRef(divResVar)));
        return VarRef(divResultB);
      }

      @Override
      public Operand case_Plus(NQJPlus plus) {
        return normalCase(Ast.Add());
      }

      @Override
      public Operand case_Minus(NQJMinus minus) {
        return normalCase(Ast.Sub());
      }

      @Override
      public Operand case_Equals(NQJEquals equals) {
        Operator op = Eq();
        Operand right = tr.exprRvalue(e.getRight());
        TemporaryVar result = TemporaryVar("res" + op.getClass().getSimpleName());
        right = tr.addCastIfNecessary(right, left.calculateType());
        tr.addInstruction(BinaryOperation(result, left, op, right));
        return VarRef(result);
      }

      @Override
      public Operand case_Less(NQJLess less) {
        return normalCase(Ast.Slt());
      }
    });
  }

  @Override
  public Operand case_ExprNull(NQJExprNull e) {
    return Ast.Nullpointer();
  }

  @Override
  public Operand case_Number(NQJNumber e) {
    return ConstInt(e.getIntValue());
  }

  @Override
  public Operand case_FunctionCall(NQJFunctionCall e) {
    // special case: printInt
    if (e.getMethodName().equals("printInt")) {
      NQJExpr arg1 = e.getArguments().get(0);
      Operand op = tr.exprRvalue(arg1);
      tr.addInstruction(Ast.Print(op));
      return ConstInt(0);
    } else {
      OperandList args = tr.getArgumentsFromCall(e);

      // lookup in global functions
      Proc proc = tr.loadFunctionProc(e.getFunctionDeclaration());

      // do the call
      TemporaryVar result = TemporaryVar(e.getMethodName() + "_result");
      tr.addInstruction(Ast.Call(result, ProcedureRef(proc), args));
      return VarRef(result);
    }
  }

  @Override
  public Operand case_BoolConst(NQJBoolConst e) {
    return Ast.ConstBool(e.getBoolValue());
  }

  @Override
  public Operand case_Read(NQJRead read) {
    TemporaryVar res = TemporaryVar("t");
    Operand op = tr.exprLvalue(read.getAddress());
    tr.addInstruction(Ast.Load(res, op));
    return VarRef(res);
  }

  @Override
  public Operand case_MethodCall(NQJMethodCall e) {
    NQJFunctionDecl f = null;
    Operand addr = null;

    if (e.getReceiver() instanceof NQJExprThis) {
      Proc proc = tr.getProcByFuncDecl(e.getFunctionDeclaration());
      Parameter thisParameter = tr.getThisParameter();

      OperandList args = tr.getArgumentsFromCall(e);
      args.addFront(VarRef(thisParameter));

      TemporaryVar result = TemporaryVar(e.getMethodName() + "_result");
      tr.addInstruction(Ast.Call(result, ProcedureRef(proc), args));

      return VarRef(result);
    } else if (e.getReceiver() instanceof NQJRead) {
      String varName = ((NQJVarUse) ((NQJRead) e.getReceiver()).getAddress()).getVarName();
      NQJVarDecl declByName = tr.getDeclByName(varName);

      // load the object that made the call
      TemporaryVar var = tr.getLocalVarLocation(declByName);
      TemporaryVar ptr = TemporaryVar("objectPtr");
      tr.addInstruction(Load(ptr, VarRef(var)));

      addr = VarRef(ptr);
      f = e.getFunctionDeclaration();
    } else if (e.getReceiver() instanceof NQJNewObject) {
      // case_NewObject is used to get the address of newly created object
      addr = case_NewObject((NQJNewObject) e.getReceiver());
      f = e.getFunctionDeclaration();

    } else if (e.getReceiver() instanceof NQJMethodCall) {
      // recursively find the left most non-NQJMethodCall receiver
      addr = case_MethodCall((NQJMethodCall) e.getReceiver());
      f = e.getFunctionDeclaration();
    }

    OperandList args = tr.getArgumentsFromCall(e);
    args.addFront(addr.copy());

    NQJClassDecl c = tr.getClassDeclByFunctionDecl(f);
    int index = tr.getIndexOfMethodByName(c.getName(), f.getName());

    // in the following the corresponding proc is called
    // vt is used to locate it
    TemporaryVar vtAddr = TemporaryVar("vtAddr");
    tr.addInstruction(GetElementPtr(vtAddr, addr.copy(),
        OperandList(ConstInt(0), ConstInt(0))));

    TemporaryVar vtLoad = TemporaryVar("vtVal");
    tr.addInstruction(Load(vtLoad, VarRef(vtAddr)));

    TemporaryVar procAddr = TemporaryVar("procAddr");
    tr.addInstruction(GetElementPtr(procAddr, VarRef(vtLoad),
        OperandList(ConstInt(0), ConstInt(index))));

    TemporaryVar procLoad = TemporaryVar("procVal");
    tr.addInstruction(Load(procLoad, VarRef(procAddr)));

    TemporaryVar result = TemporaryVar(e.getMethodName() + "_result");
    tr.addInstruction(Ast.Call(result, VarRef(procLoad), args));

    return VarRef(result);
  }

  @Override
  public Operand case_NewObject(NQJNewObject e) {
    NQJClassDecl tc = tr.getClassDeclByName(e.getClassName());
    Operand proc = tr.getNewObjectFunc(tc);
    TemporaryVar res = TemporaryVar("newObject");
    // call object constructor
    tr.addInstruction(Ast.Call(res, proc, OperandList()));
    return VarRef(res);
  }

  @Override
  public Operand case_ExprThis(NQJExprThis e) {
    return VarRef(tr.getThisParameter());
  }

}
