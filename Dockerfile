FROM openjdk:17-jdk-alpine

RUN mvn clean package -DskipTests

COPY target/EvilHamster-0.0.1-SNAPSHOT.jar EvilHamster-0.0.1-SNAPSHOT.jar

ENTRYPOINT ["java","-jar","/EvilHamster-0.0.1-SNAPSHOT.jar"]