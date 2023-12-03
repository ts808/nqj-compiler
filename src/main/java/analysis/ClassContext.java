package analysis;


import notquitejava.ast.NQJClassDecl;
import notquitejava.ast.NQJFunctionDecl;
import notquitejava.ast.NQJVarDecl;

import java.util.HashMap;
import java.util.Map;

/**
 * Auxiliary structure to conveniently operate with fields and methods
 */
public class ClassContext {

  private final NQJClassDecl decl;
  private Map<String, NQJVarDecl> fields = new HashMap<>();
  private Map<String, NQJFunctionDecl> methods = new HashMap<>();

  public ClassContext(NQJClassDecl decl) {
    this.decl = decl;
  }

  public ClassContext(NQJClassDecl decl, Map<String, NQJVarDecl> fields, Map<String, NQJFunctionDecl> methods) {
    this.decl = decl;
    this.fields = new HashMap<>(fields);
    this.methods = new HashMap<>(methods);
  }

  public NQJVarDecl lookupField(String name) {
    return fields.get(name);
  }

  public NQJClassDecl getDecl() {
    return decl;
  }

  public String getName() {
    return decl.getName();
  }

  public NQJFunctionDecl lookupMethod(String name) {
    return methods.get(name);
  }

  public void putField(String name, NQJVarDecl var) {
    fields.putIfAbsent(name, var);
  }

  public void putMethod(String name, NQJFunctionDecl method) {
    methods.putIfAbsent(name, method);
  }

  public ClassContext copy() {
    return new ClassContext(this.decl, this.fields, this.methods);
  }

}
