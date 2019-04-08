package deors.demos.java.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

public class HelloServiceTest {

    @Test
    @DisplayName("the hello greeting should be correct")
    public void testHelloGreeting() {
        var service = new HelloService();
        assertEquals("Hello!", service.getHelloGreeting());
    }
}
