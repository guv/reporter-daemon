# reporter daemon for CTest

This is a monitoring software for CTest using the reports API.
This software connects to the configured CTest servers and checks the stored reports for errors.
Found errors are reported to the specified email addresses.
You can either provided an email account with password or use sendmail.

## Getting Started

These instructions will explain you how to setup the reporter daemon. 

Download a release version of reporter-daemon: 

* [Version 0.1.2](https://github.com/sysbio-bioinf/reporter-daemon/releases/download/0.1.2/reporter-daemon-0.1.2.jar)

An initial configuration can be created as follows:

```bash
java -jar reporter-daemon init -c daemon.conf
```

The generated configuration file `daemon.conf` looks similar to the following:

```clojure
{:encrypted? false,
 :console-log? true,
 :timezone "Europe/Berlin",
 :storage {:database "reports.db"},
 :certificates {:path "certificates"},

 :email
 {:settings
  {:host "mail.uni-ulm.de",
   :tls :yes,
   :port 587,
   :user "jsmith",
   :pass "secret"},
  :sender "reporter@example.org",
  :receiver "info-list@example.org"}
 
 :servers
 [{:name "CTest",
   :url "https://localhost:8443",
   :user "reporter",
   :password "reporter",
   :delete-reports? false,
   :error-report {:minute 0, :period 2},
   :memory-check {:minute 0, :period 2, :warn-at 0.75},
   :daily-summary {:hour 0, :minute 0}}]}
```

You need to adjust this configuration to your needs (see [Configuration](#configuration)).
The reporter-daemon is started as follows:

```bash
java -jar reporter-daemon run -c daemon.conf
``` 


## Configuration

The following basic settings are required:

```clojure
{:encrypted? false,
 :console-log? true,
 :timezone "Europe/Berlin",
 :storage {:database "reports.db"},
 :certificates {:path "resources"},
 ...
}
```

The effect of these settings is described in the following:

`:encrypted?`
 : indicates wether account names and passwords are encrypted in this configuration (see [Encryption](#encryption)).

`:console-log?`
 : specifies whether the logging output is also printed to the standard output. For debugging set this to `true`. This should be set to `false` in production.
 
`:timezone`
 : specifies the timezone to use to render timestamps as date-time representations.

`:storage :database`
 : specifies the name of the database to store the downloaded reports.
 
`:certificates :path`
 : specifies the directory that contains server certificates to trust (if needed)

### Certificates

Depending on the Java version that is used to run the reporter-daemon,
it is possible that the JVM does not trust certificates from certain authorities (e.g. [Let's Encrypt](https://letsencrypt.org/)).

Therefore, report-daemon has a `download` task that you can use to download
the untrusted server certificates to a directory, review them and configure
reporter-daemon to trust those certificates (`{:certificates {:path "mycertificates"}}`).

The `download` task is used as follows:

```bash
java -jar reporter-daemon download --host my.server.org --port 443 --target myserver.pem
```

### Email

A minimal email configuration that uses `sendmail` looks as follows:

```clojure
:email
 {:sender "reporter@example.org",
  :receiver "info-list@example.org"}
```

An email configuration that uses a specific email account can be specified as follows:

```clojure
:email
 {:settings
  {:host "mail.uni-ulm.de",
   :tls :yes,
   :port 587,
   :user "jsmith",
   :pass "secret"},
  :sender "reporter@example.org",
  :receiver "info-list@example.org"}
```

`:host`
 : URL of the email server

`:tls`
 : specifies whether the communication to the email server is encrypted via TLS
 
`:user`
 : specifies the user name of the email account
 
`:pass`
 : specifies the password of the email account

### CTest Servers

One or more CTest servers can be specified for monitoring:

```clojure
:servers
 [{:name "CTest #1",
   :url "https://localhost:8443",
   :user "reporter",
   :password "reporter",
   :delete-reports? false,
   :error-report {:minute 0, :period 2},
   :memory-check {:minute 0, :period 2, :warn-at 0.75},
   :daily-summary {:hour 0, :minute 0}}
  {:name "CTest #2",
   :url "https://ctest.server.org",
   :user "report",
   :password "secret",
   :delete-reports? true,
   :error-report {:minute 0, :period 15}}]
```

A server configuration consists of the following settings:

`:name`
 : specifies the name of the monitored server to use in email notifications.
 
`:url`
 : specifies the URL of the monitored server.
 
`:user`
 : specifies the user name with the `reporter` role to access the CTest server.

`:password`
 : specifies the password of the user to access the CTest server.
 
`:delete-reports?`
 : specifies whether the downloaded reports shall be deleted on the CTest server.
 
The following settings are optional, if you include them,
the corresponding check will be performed with the specified schedule.

`:error-report`
 : specifies that the check for error reports is performed every `:period` minutes
   starting at `:minute` of the current hour (application startup).
   An email will be sent, if new errors have been logged on the server.

`:memory-check`
 : specifies that the memory check is performed every `:period` minutes
   starting at `:minute` of the current hour (application startup).
   An email will be sent, if the memory consumption is larger than `:warn-at` times of the maximum memory.
   
`:daily-summary`
 : specifies that a daily summary email is sent at `:hour` hour and `:minute` minute.
   The summary includes plots of the number of tests and views per day 
   and the number of the tests per hour of the previous day.

### Encryption

Having plain text passwords in the configuration file, is seldomly desired.
With the `encrypt` task, the passwords in the specified configuration file
can be encrypted using a master password.

```bash
java -jar reporter-daemon encrypt -c daemon.conf
Enter password:
```

The encrypted version of the initial configuration file looks as follows:

```clojure
{:encrypted? true,
 :console-log? true,
 :storage {:database "reports.db"},
 :certificates {:path "resources"},
 :servers
 [{:name "CTest",
   :url "https://localhost:8443",
   :user
   "IMLascB82gI3j5ESsxP663HC0fw8AxxyuH92ZNSDzzgynR1MzBAym1hbl4T4lO4ngxHlvDBsDm6n",
   :password
   "IMcXtULm0jP/pwoyzQGg4rprMYMjC+HXX3FKNTZhXxedYJboCHByYPF9rm0nP9pWKbqDSZ4cmgFd",
   :delete-reports? false,
   :error-report {:minute 0, :period 2},
   :memory-check {:minute 0, :period 2, :warn-at 0.75},
   :daily-summary {:hour 0, :minute 0}}],
 :timezone "Europe/Berlin",
 :email
 {:settings
  {:host "mail.uni-ulm.de",
   :tls :yes,
   :port 587,
   :user
   "IM5ANXh0QiJqcgrjQrdNyjsMwk4jvLat9Kare+6jQKFpgbNri1EeF/kaTzwFx6cSuR8+OtG0cA==",
   :pass
   "IHIrd812FsKNtryE6mP4W6K0sze27PSEbK7SG6LHWubtOoHOppXelUGUzy6VtNh+KdoNiMBjNQ=="},
  :sender "reporter@example.org",
  :receiver "info-list@example.org"}}
```

The parameter `:enrypted?` is set to `true` so that reporter-daemon will ask for a password on startup.

### Linux daemon

You can run reporter-daemon as a Linux daemon.
An exemplary startup script can be found at [daemon/reporter.daemon](daemon/reporter.daemon).
For encrypted configurations, you have to modify the provided script 
to pass the master password to standard input of reporter-daemon.


## License

Copyright © 2020-present Gunnar Völkel

This project is licensed under the Eclipse Public License v2.0 (EPL-2.0).