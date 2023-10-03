package deors.demos.java.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit.jupiter.SpringExtension;

@ExtendWith(SpringExtension.class)
@SpringBootTest
public class HelloServiceTest {

    @Autowired
    private HelloService helloService;

    @Test
    @DisplayName("the hello greeting should be correct when returned by the service")
    public void testHelloGreeting() {
        assertEquals("Hello!", helloService.getHelloGreeting());
    }

    @Test
    @DisplayName("the hello greeting with nameshould be correct when returned by the service")
    public void testHelloWithNameGreeting() {
        assertEquals("Hello!, John", helloService.getHelloGreeting("John"));
    }
}
