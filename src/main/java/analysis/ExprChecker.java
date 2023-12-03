package analysis;

import analysis.TypeContext.VarRef;
import notquitejava.ast.*;

import java.util.HashMap;
import java.util.Map;

/**
 * Matcher implementation for expressions returning a NQJ type.
 */
public class ExprChecker implements NQJExpr.Matcher<Type>, NQJExprL.Matcher<Type> {
  private final Analysis analysis;
  private final TypeContext ctxt;
  private final ClassContext clsCtxt;

  public ExprChecker(Analysis analysis, TypeContext ctxt, ClassContext clsCtxt) {
    this.analysis = analysis;
    this.ctxt = ctxt;
    this.clsCtxt = clsCtxt;
  }

  Type check(NQJExpr e) {
    return e.match(this);
  }

  Type check(NQJExprL e) {
    return e.match(this);
  }

  void expect(NQJExpr e, Type expected) {
    Type actual = check(e);
    if (!actual.isSubtypeOf(expected)) {
      analysis.addError(e, "Expected expression of type " + expected
          + " but found " + actual + ".");
    }
  }

  Type expectArray(NQJExpr e) {
    Type actual = check(e);
    if (!(actual instanceof ArrayType)) {
      analysis.addError(e, "Expected expression of array type,  but found " + actual + ".");
      return Type.ANY;
    } else {
      return actual;
    }
  }

  private String lookupDeclType(String name) {
    // lookup declaration inside current block context
    VarRef varRef = ctxt.lookupVar(name);
    // lookup declaration inside current class context
    NQJVarDecl varDecl = clsCtxt.lookupField(name);

    if (varRef == null && varDecl == null) {
      throw new RuntimeException("Variable " + name + " is not defined");
    }

    // normally a variable can only be declared in one of the scopes
    // but it can still be declared in two places simultaneously
    // in such case it's shadowing and we need to return the block decl
    NQJTypeClass type = varRef != null
        ? (NQJTypeClass) (varRef.getDecl().getType())
        : (NQJTypeClass) varDecl.getType();

    return type.getName();
  }

  @Override
  public Type case_ExprUnary(NQJExprUnary exprUnary) {
    Type t = check(exprUnary.getExpr());
    return exprUnary.getUnaryOperator().match(new NQJUnaryOperator.Matcher<Type>() {

      @Override
      public Type case_UnaryMinus(NQJUnaryMinus unaryMinus) {
        expect(exprUnary.getExpr(), Type.INT);
        return Type.INT;
      }

      @Override
      public Type case_Negate(NQJNegate negate) {
        expect(exprUnary.getExpr(), Type.BOOL);
        return Type.BOOL;
      }
    });
  }

  @Override
  public Type case_MethodCall(NQJMethodCall methodCall) {
    String varClsName = null;

    if (methodCall.getReceiver() instanceof NQJExprThis) {
      NQJFunctionDecl f = clsCtxt.lookupMethod(methodCall.getMethodName());
      if (f != null) {
        return argumentsCheck(methodCall, f);
      }
    } else if (methodCall.getReceiver() instanceof NQJNewObject) {
      varClsName = ((NQJNewObject) methodCall.getReceiver()).getClassName();
    } else if (methodCall.getReceiver() instanceof NQJRead) {
      NQJRead receiver = (NQJRead) methodCall.getReceiver();

      if (receiver.getAddress() instanceof NQJVarUse) {
        String varName = ((NQJVarUse) receiver.getAddress()).getVarName();
        // check if the variable was declared inside class or method
        varClsName = lookupDeclType(varName);
      }
    } else if (methodCall.getReceiver() instanceof NQJMethodCall) {
      NQJMethodCall receiver = (NQJMethodCall) methodCall.getReceiver();
      //recursion
      Type type = case_MethodCall(receiver);
      varClsName = type.getName();
    }

    ClassContext context = analysis.lookupContext(varClsName);

    // if class with this name exists
    if (context != null) {
      NQJFunctionDecl f = context.lookupMethod(methodCall.getMethodName());

      // if method with this name exists
      if (f != null) {
        return argumentsCheck(methodCall, f);
      }
    }

    analysis.addError(methodCall, "Method " + methodCall.getMethodName() + " is not defined.");
    return Type.ANY;
  }


  @Override
  public Type case_ArrayLength(NQJArrayLength arrayLength) {
    expectArray(arrayLength.getArrayExpr());
    return Type.INT;
  }


  @Override
  public Type case_ExprThis(NQJExprThis exprThis) {
    return new TypeClass(clsCtxt.getName(), analysis.getNameTable());
  }

  @Override
  public Type case_ExprBinary(NQJExprBinary exprBinary) {
    return exprBinary.getOperator().match(new NQJOperator.Matcher<>() {
      @Override
      public Type case_And(NQJAnd and) {
        expect(exprBinary.getLeft(), Type.BOOL);
        expect(exprBinary.getRight(), Type.BOOL);
        return Type.BOOL;
      }

      @Override
      public Type case_Times(NQJTimes times) {
        return case_intOperation();
      }

      @Override
      public Type case_Div(NQJDiv div) {
        return case_intOperation();
      }

      @Override
      public Type case_Plus(NQJPlus plus) {
        return case_intOperation();
      }

      @Override
      public Type case_Minus(NQJMinus minus) {
        return case_intOperation();
      }

      private Type case_intOperation() {
        expect(exprBinary.getLeft(), Type.INT);
        expect(exprBinary.getRight(), Type.INT);
        return Type.INT;
      }

      @Override
      public Type case_Equals(NQJEquals equals) {
        Type l = check(exprBinary.getLeft());
        Type r = check(exprBinary.getRight());
        if (!l.isSubtypeOf(r) && !r.isSubtypeOf(l)) {
          analysis.addError(exprBinary, "Cannot compare types " + l + " and " + r + ".");
        }
        return Type.BOOL;
      }

      @Override
      public Type case_Less(NQJLess less) {
        expect(exprBinary.getLeft(), Type.INT);
        expect(exprBinary.getRight(), Type.INT);
        return Type.BOOL;
      }
    });
  }

  @Override
  public Type case_ExprNull(NQJExprNull exprNull) {
    return Type.NULL;
  }

  private Type argumentsCheck(NQJExpr call, NQJFunctionDecl f) {
    NQJExprList args = null;

    if (call instanceof NQJMethodCall) {
      args = ((NQJMethodCall) call).getArguments();
      ((NQJMethodCall) call).setFunctionDeclaration(f);
    } else if (call instanceof NQJFunctionCall) {
      args = ((NQJFunctionCall) call).getArguments();
      ((NQJFunctionCall) call).setFunctionDeclaration(f);
    }

    NQJVarDeclList params = f.getFormalParameters();

    if (args.size() < params.size()) {
      analysis.addError(call, "Not enough arguments.");
    } else if (args.size() > params.size()) {
      analysis.addError(call, "Too many arguments.");
    } else {
      for (int i = 0; i < params.size(); i++) {
        expect(args.get(i), analysis.type(params.get(i).getType()));
      }
    }

    return analysis.type(f.getReturnType());
  }

  @Override
  public Type case_FunctionCall(NQJFunctionCall functionCall) {
    NQJFunctionDecl f = analysis.getNameTable().lookupFunction(functionCall.getMethodName());

    if (f == null) {
      analysis.addError(functionCall, "Function " + functionCall.getMethodName()
          + " does not exists.");
      return Type.ANY;
    } else {
      return argumentsCheck(functionCall, f);
    }
  }

  @Override
  public Type case_Number(NQJNumber number) {
    return Type.INT;
  }

  @Override
  public Type case_NewArray(NQJNewArray newArray) {
    expect(newArray.getArraySize(), Type.INT);
    ArrayType t = new ArrayType(analysis.type(newArray.getBaseType()));
    newArray.setArrayType(t);
    return t;
  }

  @Override
  public Type case_NewObject(NQJNewObject newObject) {
    return new TypeClass(newObject.getClassName(), analysis.getNameTable());
  }

  @Override
  public Type case_BoolConst(NQJBoolConst boolConst) {
    return Type.BOOL;
  }

  @Override
  public Type case_Read(NQJRead read) {
    return read.getAddress().match(this);
  }

  @Override
  public Type case_FieldAccess(NQJFieldAccess fieldAccess) {

    if (fieldAccess.getReceiver() instanceof NQJExprThis) {
      NQJVarDecl var = clsCtxt.lookupField(fieldAccess.getFieldName());

      if (var != null) {
        return analysis.type(var.getType());
      }

    }  else if (fieldAccess.getReceiver() instanceof NQJRead) {
      NQJRead receiver = (NQJRead) fieldAccess.getReceiver();

      if (receiver.getAddress() instanceof NQJVarUse) {
        NQJVarUse address = (NQJVarUse) receiver.getAddress();
        String clsName = lookupDeclType(address.getVarName());

        // if variable was declared
        if (clsName != null) {
          // finding ClassContext by name
          ClassContext context = analysis.lookupContext(clsName);

          // if it exists
          if (context != null) {
            NQJVarDecl field = context.lookupField(fieldAccess.getFieldName());

            // if field is declared inside the class
            if (field != null) {
              return analysis.type(field.getType());
            }
          }
        }
      }
    } else if (fieldAccess.getReceiver() instanceof NQJNewObject) {
      NQJNewObject receiver = (NQJNewObject) fieldAccess.getReceiver();
      ClassContext context = analysis.lookupContext(receiver.getClassName());

      if (context != null) {
        NQJVarDecl field = context.lookupField(fieldAccess.getFieldName());

        if (field != null) {
          return analysis.type(field.getType());
        }
      }
    }

    analysis.addError(fieldAccess, "Field " + fieldAccess.getFieldName() + " is not defined.");
    return Type.ANY;
  }

  @Override
  public Type case_VarUse(NQJVarUse varUse) {
    // look for a var in the local context
    VarRef ref = ctxt.lookupVar(varUse.getVarName());
    // look for a var in the class
    NQJVarDecl clsVarRef = clsCtxt != null ? clsCtxt.lookupField(varUse.getVarName()) : null;

    // variable is not defined anywhere
    if (ref == null && clsVarRef == null) {
      analysis.addError(varUse, "Variable " + varUse.getVarName() + " is not defined.");
      return Type.ANY;
    } else if (ref == null) {
      // variable is defined in the class
      varUse.setVariableDeclaration(clsVarRef);
      return analysis.type(clsVarRef.getType());
    } else {
      // variable is defined in the local context
      varUse.setVariableDeclaration(ref.getDecl());
      return ref.type;
    }
  }

  @Override
  public Type case_ArrayLookup(NQJArrayLookup arrayLookup) {
    Type type = analysis.checkExpr(ctxt, arrayLookup.getArrayExpr());
    expect(arrayLookup.getArrayIndex(), Type.INT);
    if (type instanceof ArrayType) {
      ArrayType arrayType = (ArrayType) type;
      arrayLookup.setArrayType(arrayType);
      return arrayType.getBaseType();
    }
    analysis.addError(arrayLookup, "Expected an array for array-lookup, but found " + type);
    return Type.ANY;
  }
}
