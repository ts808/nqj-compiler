/**
 * Global functions call
 */

class A {

  int bar() {
    return bar();
  }

}

int bar() {
  return 5;
}



int main() {
  A a;
  a = new A();
  printInt(a.bar());

  return 0;
}


