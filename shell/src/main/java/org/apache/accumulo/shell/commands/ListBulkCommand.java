/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.accumulo.shell.commands;

import static com.google.common.util.concurrent.Uninterruptibles.sleepUninterruptibly;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.apache.accumulo.core.clientImpl.ClientContext;
import org.apache.accumulo.core.clientImpl.ManagerClient;
import org.apache.accumulo.core.clientImpl.thrift.ThriftNotActiveServiceException;
import org.apache.accumulo.core.manager.thrift.ManagerClientService;
import org.apache.accumulo.core.manager.thrift.ManagerMonitorInfo;
import org.apache.accumulo.core.trace.TraceUtil;
import org.apache.accumulo.shell.Shell;
import org.apache.accumulo.shell.Shell.Command;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;

public class ListBulkCommand extends Command {

  private Option tserverOption, disablePaginationOpt;

  @Override
  public String description() {
    return "lists what bulk imports are currently running in accumulo.";
  }

  @Override
  public int execute(final String fullCommand, final CommandLine cl, final Shell shellState)
      throws Exception {

    List<String> tservers;

    ManagerMonitorInfo stats;
    ManagerClientService.Iface client = null;
    ClientContext context = shellState.getContext();
    while (true) {
      try {
        client = ManagerClient.getConnectionWithRetry(context);
        stats = client.getManagerStats(TraceUtil.traceInfo(), context.rpcCreds());
        break;
      } catch (ThriftNotActiveServiceException e) {
        // Let it loop, fetching a new location
        sleepUninterruptibly(100, TimeUnit.MILLISECONDS);
      } finally {
        if (client != null)
          ManagerClient.close(client, context);
      }
    }

    final boolean paginate = !cl.hasOption(disablePaginationOpt.getOpt());

    if (cl.hasOption(tserverOption.getOpt())) {
      tservers = new ArrayList<>();
      tservers.add(cl.getOptionValue(tserverOption.getOpt()));
    } else {
      tservers = Collections.emptyList();
    }

    shellState.printLines(new BulkImportListIterator(tservers, stats), paginate);
    return 0;
  }

  @Override
  public int numArgs() {
    return 0;
  }

  @Override
  public Options getOptions() {
    final Options opts = new Options();

    tserverOption = new Option("ts", "tabletServer", true, "tablet server to list bulk imports");
    tserverOption.setArgName("tablet server");
    opts.addOption(tserverOption);

    disablePaginationOpt = new Option("np", "no-pagination", false, "disable pagination of output");
    opts.addOption(disablePaginationOpt);

    return opts;
  }

}
