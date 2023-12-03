package minillvm.analysis;

import java.util.Set;
import java.util.stream.Collectors;
import minillvm.ast.Element;
import minillvm.ast.VarRef;


/**
 * Type error for cyclic dependencies in the AST.
 */
public class LlvmTypeErrorCycle extends RuntimeException {
  private static final long serialVersionUID = 4783332528371431123L;
  private final Set<Element> visited;

  public LlvmTypeErrorCycle(Set<Element> visited) {
    this.visited = visited;
  }

  @Override
  public String getMessage() {
    return "Could not determine LLVM type because of cyclic dependencies in the LLVM AST:\n"
        + visited.stream()
        .map(elem -> {
          if (elem instanceof VarRef) {
            return "Uses variable: " + ((VarRef) elem).getVariable();
          } else {
            return "Is used in: " + elem;
          }
        })
        .collect(Collectors.joining("\n"));
  }
}
