package deors.demos.java.pipeline;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HelloController {

    @Autowired
    private HelloService helloService;

    @RequestMapping(path = "/hello", method = RequestMethod.GET)
    public String getHelloGreeting() {
        return helloService.getHelloGreeting();
    }
}
