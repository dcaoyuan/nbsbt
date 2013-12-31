nbsbt
=====

Plugin for sbt to create Netbeans project definition.

This project is derived from [https://github.com/typesafehub/sbteclipse](https://github.com/typesafehub/sbteclipse)

1. nbsbt requires sbt 0.13+

   for sbt 0.12.x, checkout branch 1.0.2-sbt-0.12.x

1. Build and publish-local

        // for master branch
        cd nbsbt
        sbt
        > clean
        > compile
        > publish-local 
        
	// for 1.0.2-sbt-0.12.x branch
        cd nbsbt
        sbt 
        > + clean 
        > + compile 
        > + publish-local

1. Add nbsbt to your plugin definition file. You can use either the global one at ~/.sbt/0.13/plugins/plugins.sbt or the project-specific one at PROJECT_DIR/project/plugins.sbt:

        // for sbt 0.13.x
        addSbtPlugin("org.netbeans.nbsbt" % "nbsbt-plugin" % "1.1.0")
        // for sbt 0.12.x
        addSbtPlugin("org.netbeans.nbsbt" % "nbsbt-plugin" % "1.0.2")

1. In sbt, you can use the command "netbeans" to generate NetBeans project files (Note: run this command under the top project):

        > netbeans

1. Or, in NetBeans, you do not need to run "netbeans" command manually, NetBeans will handle it automatically.
