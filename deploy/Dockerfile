FROM eclipse-temurin:21-jre-jammy
RUN groupadd --gid 10001 team && useradd --uid 10001 --gid team --no-create-home team \
    && install -d -o team -g team /srv/uploads
WORKDIR /srv
COPY backend.jar /srv/backend.jar
USER 10001:10001
ENTRYPOINT ["java", "-jar", "/srv/backend.jar"]
