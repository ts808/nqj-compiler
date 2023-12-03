/**
 * Overriding methods
 */

class A {

  int bar(int a) {
    return a;
  }

}

class B extends A {

  int bar(int a) {
    return a * 2;
  }

}


int main() {
  B b;
  b = new B();
  printInt(b.bar(40));
  return 0;
}


