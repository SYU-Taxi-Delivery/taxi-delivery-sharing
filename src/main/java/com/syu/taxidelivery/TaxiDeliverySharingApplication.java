package com.syu.taxidelivery;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@SpringBootApplication(scanBasePackages = {"com.syu.taxidelivery", "com.school.party"})
@EntityScan("com.school.party")
@EnableJpaRepositories("com.school.party")
public class TaxiDeliverySharingApplication {

	public static void main(String[] args) {
		SpringApplication.run(TaxiDeliverySharingApplication.class, args);
	}

}
