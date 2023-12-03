/**
 * Field accessing
 */

class A {

  B b;

  int foo() {
    b = new B();
    b.init();

    if (b.k == 72) {
      printInt(1);
    } else {
      printInt(0);
    }
    return 0;
  }

}

class B {
  int k;

  int init() {
    this.k = 72;
    return 0;
  }

}



int main() {
  A a;
  a = new A();
  a.foo();

  return 0;
}


