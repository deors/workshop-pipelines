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
public class HelloControllerTest {

	@Autowired
	private HelloController helloController;

	@Test
	@DisplayName("the hello greeting should be correct when returned by the controller")
	public void testHelloController() {
		assertEquals("Hello!", helloController.getHelloGreeting());
	}

	@Test
	@DisplayName("the hello greeting with name should be correct when returned by the controller")
	public void testHelloWithNameController() {
		assertEquals("Hello!, John", helloController.getHelloGreeting("John"));
	}
}
