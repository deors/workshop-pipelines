package deors.demos.java.pipeline;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PathVariable;
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

    @RequestMapping(path = "/hello/{name}", method = RequestMethod.GET)
    public String getHelloGreeting(@PathVariable String name) {
        return helloService.getHelloGreeting(name);
    }
}
