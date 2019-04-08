package deors.demos.java.pipeline;

import org.springframework.stereotype.Service;

@Service
public class HelloService {

    public HelloService() {
        super();
    }

    public String getHelloGreeting() {
        return "Hello!";
    }
}
