# Velocity

[![Build Status](https://img.shields.io/jenkins/s/https/ci.velocitypowered.com/job/velocity/job/master.svg)](https://ci.velocitypowered.com/job/velocity/job/master/)
[![Join our Discord](https://img.shields.io/discord/472484458856185878.svg?logo=discord&label=)](https://discord.gg/8cB9Bgf)

Velocity is a next-generation Minecraft: Java Edition proxy suite. It is
designed specifically with mass-scale Minecraft in mind.

## Goals

* Simple, easy to understand codebase.
* High performance: handle thousands of players on one proxy.
* Flexible API built for mass-scale Minecraft.
* Licensed under the MIT license.
* First-class support for Paper, Sponge, and Forge. (Other implementations
  may work, but we make every endeavor to support these server implementations
  specifically.)
  
## Building

Velocity is built with [Gradle](https://gradle.org). We recommend using the
wrapper script (`./gradlew`) as our CI builds using it.

It is sufficient to run `./gradlew build` to run the full build cycle.

## Running

Once you've built Velocity, you can copy and run the `-all` JAR from
`proxy/build/libs`. Velocity will generate a default configuration file
and you can configure it from there.

Alternatively, you can get the proxy JAR from the [downloads](https://www.velocitypowered.com/downloads)
page.

## Status

Velocity is far from finished, but most of the essential pieces you would
expect are in place. Velocity supports Minecraft 1.8-1.13. More functionality
is planned.
