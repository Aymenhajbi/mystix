package ma.mystix;

import org.springframework.boot.SpringApplication;

public class TestMystixBackendApplication {

	public static void main(String[] args) {
		SpringApplication.from(MystixBackendApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
