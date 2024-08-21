# DO NOT EDIT THIS FILE - See: https://jetty.org/docs/

[description]
Download and deploy the Test Spec webapp demo.

[environment]
ee8

[tags]
demo
webapp

[depends]
ee8-deploy
jdbc
ee8-jsp
ee8-annotations
ext
demo-realm
ee8-demo-mock-resources

[files]
basehome:modules/demo.d/ee8-demo-spec.xml|webapps/ee8-demo-spec.xml
basehome:modules/demo.d/ee8-demo-spec.properties|webapps/ee8-demo-spec.properties
maven://org.eclipse.jetty.demos/jetty-servlet4-demo-spec-webapp/${jetty.version}/war|webapps/ee8-demo-spec.war
