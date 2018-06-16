./gradlew build
docker run -m512M --cpus 2 -it -p 8080:8080 -v $PWD:/root:ro mmo-poc