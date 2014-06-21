version-range-maven-plugin
==========================

automate update process for certain dependencies by specifying version ranges

![travis build status](https://travis-ci.org/mkloeckner/version-range-maven-plugin.svg?branch=master "Travis Build Status")



# Problem:

versions-maven-plugin allows you to update versions of dependencies in your project. 
Unfortunately is doesn't work properly in conjunction with maven-release-plugin, which doesn't allow version-ranges.

...


# Solution:

a maven-plugin which allows you to specify version ranges for dependencies an resolve the latest version from a 
nexus repository manager.


