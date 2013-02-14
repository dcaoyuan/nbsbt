nbsbt
=====

Plugin for sbt to create Netbeans project definition.

This project is derivated from [sbteclipse](https://github.com/typesafehub/sbteclipse)

1. nbsbt requires sbt 0.12+

1. Add nbsbt to your plugin definition file. You can use either the global one at ~/.sbt/plugins/plugins.sbt or the project-specific one at PROJECT_DIR/project/plugins.sbt:

    addSbtPlugin("org.netbeans.sbt" % "sbt-plugin" % "1.0.0")

1. In sbt, you can use the command "netbeans" to generate NetBeans project files:

    > netbeans

4. Or, in NetBeans, you do not need to run "netbeans" command manually, NetBeans will handle it automatically.
