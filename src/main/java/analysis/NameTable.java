package analysis;

import notquitejava.ast.*;

import java.util.*;

/**
 * Name table for analysis class hierarchies.
 */
public class NameTable {
  private final Map<Type, ArrayType> arrayTypes = new HashMap<>();
  private final Map<String, NQJFunctionDecl> globalFunctions = new HashMap<>();
  private final Map<String, String> inheritanceMap = new HashMap<>();
  private final Analysis analysis;

  NameTable(Analysis analysis, NQJProgram prog) {
    this.analysis = analysis;
    this.globalFunctions.put("printInt", NQJ.FunctionDecl(NQJ.TypeInt(), "main",
        NQJ.VarDeclList(NQJ.VarDecl(NQJ.TypeInt(), "elem")), NQJ.Block()));
    for (NQJFunctionDecl f : prog.getFunctionDecls()) {
      var old = globalFunctions.put(f.getName(), f);
      if (old != null) {
        analysis.addError(f, "There already is a global function with name " + f.getName()
            + " defined in " + old.getSourcePosition());
      }
    }

    Set<String> classNames = new HashSet<>();

    // check for name uniqueness
    for (NQJClassDecl c : prog.getClassDecls()) {
      if (!classNames.add(c.getName())) {
        analysis.addError(c, "There already is a class with name " + c.getName()
            + " defined");
      }
    }

    for (NQJClassDecl c : prog.getClassDecls()) {
      // if current class has a superclass
      if (c.getExtended() instanceof NQJExtendsClass) {
        String extended = ((NQJExtendsClass) c.getExtended()).getName();

        if (extended.equals(c.getName())) {
          analysis.addError(c, "Class cannot inherit from itself");
        } else if (!classNames.contains(extended)) {
          analysis.addError(c, "Class " + extended + " is not defined");
        } else {
          inheritanceMap.put(c.getName(), extended);
          c.setDirectSuperClass(analysis.getClassDeclByName(extended));
        }
      }
    }

    // check for cyclic inheritance
    for (NQJClassDecl c : prog.getClassDecls()) {
      if (c.getExtended() instanceof NQJExtendsClass) {
        getAncestorNames(c.getName());
      }
    }
  }


  public NQJFunctionDecl lookupFunction(String functionName) {
    return globalFunctions.get(functionName);
  }

  /**
   * Transform base type to array type.
   */
  public ArrayType getArrayType(Type baseType) {
    if (!arrayTypes.containsKey(baseType)) {
      arrayTypes.put(baseType, new ArrayType(baseType));
    }
    return arrayTypes.get(baseType);
  }

  public boolean isSubclass(String c, String parent) {
    if (parent == null) {
      return false;
    }

    for (String a : getAncestorNames(c)) {
      if (a.equals(parent)) {
        return true;
      }
    }

    return false;
  }

  public String getParentName(String c) {
    return inheritanceMap.get(c);
  }

  public List<String> getAncestorNames(String c) {
    List<String> ancestors = new LinkedList<>();
    Set<String> names = new HashSet<>();
    String a = inheritanceMap.get(c);


    while (a != null) {
      if (names.contains(a)) {
        analysis.addError(analysis.getClassDeclByName(c), "Cyclic inheritance occurred");
        break;
      }

      ancestors.add(a);
      names.add(a);
      a = inheritanceMap.get(a);
    }

    return ancestors;

  }

}
