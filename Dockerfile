
#
# Build stage
#
RUN ./mvn -f /home/app/pom.xml clean package

#
# Package stage
#
FROM openjdk:17-jdk-alpine
COPY --from=build /home/app/target/EvilHamster-0.0.1-SNAPSHOT.jar /usr/local/lib/EvilHamster-0.0.1-SNAPSHOT.jar
EXPOSE 8080
ENTRYPOINT ["java","-jar","/usr/local/lib/EvilHamster-0.0.1-SNAPSHOT.jar"]