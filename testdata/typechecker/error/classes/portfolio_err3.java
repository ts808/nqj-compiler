/**
 * overloading of B.foo is illegal
 */

class A {
  int foo(int a, int b) {
    return 0;
  }
}

class B extends A {
  int foo(int a, int b, int c) {
    return 0;
  }
}

  int main() {
    return 0;
  }