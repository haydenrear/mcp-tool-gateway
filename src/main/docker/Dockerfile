FROM localhost:5005/jdk

RUN apt-get -y update && apt-get -y upgrade

COPY ./mcp-tool-gateway.jar .
CMD java -jar mcp-tool-gateway.jar