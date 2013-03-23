#!/usr/bin/python

import argparse
import boto
import os, time, sys, socket
import json

'''
    Simple EC2 utility:
     
       * to setup clooud of 5 nodes: ./ec2_cmd.py create --instances 5
       * to terminated the cloud   : ./ec2_cmd.py terminate --hosts <host file returned by previous command>
'''

DEFAULT_NUMBER_OF_INSTANCES = 4
DEFAULT_HOSTS_FILENAME = 'ec2-config-{0}.json'

DEFAULT_REGION = 'us-east-1'
'''
Default EC2 instance setup
'''
DEFAULT_EC2_INSTANCE_CONFIGS = {
  'us-east-1':{
              'image_id'        : 'ami-b85cc4d1', # 'ami-cd9a11a4',
              'security_groups' : [ 'MrJenkinsTest' ],
              'key_name'        : 'mrjenkins_test',
              'instance_type'   : 'm1.xlarge',
              'pem'             : '~/.ec2/keys/mrjenkins_test.pem',
              'username'        : '0xdiag',      
              'aws_credentials' : '~/.ec2/AwsCredentials.properties',
              'region'          : 'us-east-1',
             },
  'us-west-1':{
              'image_id'        : 'ami-a6cbe6e3', # 'ami-cd9a11a4',
              'security_groups' : [ 'MrJenkinsTest' ],
              'key_name'        : 'mrjenkins_test',
              'instance_type'   : 'm1.xlarge',
              'pem'             : '~/.ec2/keys/mrjenkins_test.pem',
              'username'        : '0xdiag',      
              'aws_credentials' : '~/.ec2/AwsCredentials.properties',
              'region'          : 'us-west-1',
             },
}

''' Memory mappings for instance kinds '''
MEMORY_MAPPING = {
    'm1.large'   : { 'xmx' : 5  },  
    'm1.xlarge'  : { 'xmx' : 11 }, 
    'm2.xlarge'  : { 'xmx' : 13 },  
    'm2.2xlarge' : { 'xmx' : 30 },  
    'm2.4xlarge' : { 'xmx' : 60 },  
    'm3.xlarge'  : { 'xmx' : 11 },  
    'm3.2xlarge' : { 'xmx' : 24 },  
}

''' EC2 API default configuration. The corresponding values are replaces by EC2 user config. '''
EC2_API_RUN_INSTANCE = {
'image_id'        :None,
'min_count'       :1,
'max_count'       :1,
'key_name'        :None,
'security_groups' :None,
'user_data'       :None,
'addressing_type' :None,
'instance_type'   :None,
'placement'       :None,
'monitoring_enabled':False,
'subnet_id'       :None,
'block_device_map':None,
'disable_api_termination':False,
'instance_initiated_shutdown_behavior':None
}

# Duplicated from h2o_cmd to run in python 
def dot():
    sys.stdout.write('.')
    sys.stdout.flush()

def port_live(ip, port):
    s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    try:
        s.connect((ip,port))
        s.shutdown(2)
        return True
    except:
        return False

def inheritparams(parent, kid):
    newkid = {}
    for k,v in kid.items():
        if parent.has_key(k):
            newkid[k] = parent[k]

    return newkid

def find_file(base):
    f = base
    if not os.path.exists(f): f = os.path.expanduser(base)
    if not os.path.exists(f): f = os.path.expanduser("~")+ '/' + base
    if not os.path.exists(f):
        raise Exception("\033[91m[ec2] Unable to find config %s \033[0m" % base)
    return f

''' Returns a boto connection to given region ''' 
def ec2_connect(region):
    import boto.ec2
    conn = boto.ec2.connect_to_region(region)
    if not conn:
        raise Exception("\033[91m[ec2] Cannot create EC2 connection into {0} region!\033[0m".format(region))

    return conn


''' Run number of EC2 instance.
Waits forthem and optionaly waits for ssh service.
'''
def run_instances(count, ec2_config, region, waitForSSH=True):
    '''Create a new reservation for count instances'''
    ec2_config.setdefault('min_count', count)
    ec2_config.setdefault('max_count', count)

    ec2params = inheritparams(ec2_config, EC2_API_RUN_INSTANCE)

    reservation = None
    conn = ec2_connect(region)
    try:
        reservation = conn.run_instances(**ec2params)
        log('Waiting for {0} EC2 instances {1} to come up, this can take 1-2 minutes.'.format(len(reservation.instances), reservation.instances))
        start = time.time()
        for instance in reservation.instances:
            while instance.update() == 'pending':
               time.sleep(1)
               dot()

            if not instance.state == 'running':
                raise Exception('\033[91m[ec2] Error waiting for running state. Instance is in state {0}.\033[0m'.format(instance.state))

        log('Instances started in {0} seconds'.format(time.time() - start))
        log('Instances: ')
        for inst in reservation.instances: log("   {0} ({1}) : public ip: {2}, private ip: {3}".format(inst.public_dns_name, inst.id, inst.ip_address, inst.private_ip_address))
        
        if waitForSSH:
            wait_for_ssh([ i.ip_address for i in reservation.instances ])

        return reservation
    except:
        print "\033[91mUnexpected error\033[0m :", sys.exc_info()
        if reservation:
            terminate_reservation(reservation, region)
        raise

''' Wait for ssh port 
'''
def ssh_live(ip, port=22):
    return port_live(ip,port)

def terminate_reservation(reservation, region):
    terminate_instances([ i.id for i in reservation.instances ], region)

def terminate_instances(instances, region):
    '''terminate all the instances given by its ids'''
    if not instances: return
    conn = ec2_connect(region)
    log("Terminating instances {0}.".format(instances))
    conn.terminate_instances(instances)
    log("Done")

def stop_instances(instances, region):
    '''stop all the instances given by its ids'''
    if not instances: return
    conn = ec2_connect(region)
    log("Stopping instances {0}.".format(instances))
    conn.stop_instances(instances)
    log("Done")

def start_instances(instances, region):
    '''Start all the instances given by its ids'''
    if not instances: return
    conn = ec2_connect(region)
    log("Starting instances {0}.".format(instances))
    conn.start_instances(instances)
    log("Done")

def reboot_instances(instances, region):
    '''Reboot all the instances given by its ids'''
    if not instances: return
    conn = ec2_connect(region)
    log("Rebooting instances {0}.".format(instances))
    conn.reboot_instances(instances)
    log("Done")

def wait_for_ssh(ips, port=22, skipAlive=True, requiredsuccess=3):
    ''' Wait for ssh service to appear on given hosts'''
    log('Waiting for SSH on following hosts: {0}'.format(ips))
    for ip in ips:
        if not skipAlive or not ssh_live(ip, port): 
            log('Waiting for SSH on instance {0}...'.format(ip))
            count = 0
            while count < requiredsuccess:
                if ssh_live(ip, port):
                    count += 1
                else:
                    count = 0
                time.sleep(1)
                dot()


def dump_hosts_config(ec2_config, reservation, filename=DEFAULT_HOSTS_FILENAME):
    if not filename: filename=DEFAULT_HOSTS_FILENAME

    cfg = {}
    cfg['aws_credentials'] = find_file(ec2_config['aws_credentials'])
    cfg['username']        = ec2_config['username'] 
    cfg['key_filename']    = find_file(ec2_config['pem'])
    cfg['h2o_per_host']    = 1
    cfg['java_heap_GB']    = MEMORY_MAPPING[ec2_config['instance_type']]['xmx']
    cfg['java_extra_args'] = '-XX:MaxDirectMemorySize=1g'
    cfg['base_port']       = 54321
    cfg['ip'] = [ i.private_ip_address for i in reservation.instances ]
    cfg['ec2_instances']   = [ { 'id': i.id, 'private_ip_address': i.private_ip_address, 'public_ip_address': i.ip_address, 'public_dns_name': i.public_dns_name } for i in reservation.instances ]
    cfg['ec2_reservation_id']  = reservation.id
    cfg['ec2_region']      = ec2_config['region']
    cfg['redirect_import_folder_to_s3_path'] = True
    # put ssh commands into comments
    cmds = get_ssh_commands(ec2_config, reservation)
    idx  = 1
    for cmd in cmds: 
        cfg['ec2_comment_ssh_{0}'.format(idx)] = cmd
        idx += 1
    # save config
    filename = filename.format(reservation.id)
    with open(filename, 'w+') as f:
        f.write(json.dumps(cfg, indent=4))

    log("Host config dumped into {0}".format(filename))
    log("To terminate instances call:")
    log("\033[93mpython ec2_cmd.py terminate --hosts {0}\033[0m".format(filename))
    log("To watch cloud in browser follow address:")
    log("   http://{0}:{1}".format(reservation.instances[0].public_dns_name, cfg['base_port']))

def get_ssh_commands(ec2_config, reservation):
    cmds = []
    for i in reservation.instances:
        cmds.append( "ssh -i ~/.ec2/keys/mrjenkins_test.pem ubuntu@{0}".format(i.private_ip_address) )
    return cmds

def dump_ssh_commands(ec2_config, reservation):
    cmds = get_ssh_commands(ec2_config, reservation)
    for cmd in cmds:
        print cmd 

def load_ec2_region(region):
    for r in DEFAULT_EC2_INSTANCE_CONFIGS:
        if r == region:
            return region

    raise Exception('\033[91m[ec2] Unsupported EC2 region: {0}. The available regions are: {1}\033[0m'.format(region, [r for r in DEFAULT_EC2_INSTANCE_CONFIGS ]))

def load_ec2_config(config_file, region):
    if config_file:
        f = find_file(config_file)
        with open(f, 'rb') as fp:
             ec2_cfg = json.load(fp)
    else:
        ec2_cfg = {}

    for k,v in DEFAULT_EC2_INSTANCE_CONFIGS[region].items():
        ec2_cfg.setdefault(k, v)

    return ec2_cfg

def load_hosts_config(config_file):
    f = find_file(config_file)
    with open(f, 'rb') as fp:
         host_cfg = json.load(fp)
    return host_cfg

def log(msg):
    print "\033[92m[ec2] \033[0m", msg

def invoke_hosts_action(action, hosts_config):
    ids = [ inst['id'] for inst in hosts_config['ec2_instances'] ]
    ips = [ inst['private_ip_address'] for inst in hosts_config['ec2_instances'] ]
    region = hosts_config['ec2_region']

    if (action == 'terminate'):
        terminate_instances(ids, region)
    elif (action == 'stop'):
        stop_instances(ids, region)
    elif (action == 'reboot'):
        reboot_instances(ids, region)
        wait_for_ssh(ips, skipAlive=False, requiredsuccess=10)
    elif (action == 'start'):
        start_instances(ids, region)
        # FIXME after start instances receive new IPs: wait_for_ssh(ips)
    elif (action == 'distribute_h2o'):
        pass
    elif (action == 'start_h2o'):
        pass
    elif (action == 'stop_h2o'):
        pass


def merge_reservations(reservations):
    pass

def main():
    parser = argparse.ArgumentParser(description='H2O EC2 instances launcher')
    parser.add_argument('action', choices=['create', 'terminate', 'stop', 'reboot', 'start', 'distribute_h2o', 'start_h2o', 'stop_h2o', 'show_defaults', 'merge_reservations'],  help='EC2 instances action')
    parser.add_argument('-c', '--config',    help='Configuration file to configure NEW EC2 instances (if not specified default is used - see "show_defaults")', type=str, default=None)
    parser.add_argument('-i', '--instances', help='Number of instances to launch', type=int, default=DEFAULT_NUMBER_OF_INSTANCES)
    parser.add_argument('-H', '--hosts',     help='Hosts file describing existing "EXISTING" EC2 instances ', type=str, default=None)
    parser.add_argument('-r', '--region',    help='Specifies target create region', type=str, default=DEFAULT_REGION)
    parser.add_argument('--reservations',    help='Comma separated list of reservation IDs', type=str, default=None)
    args = parser.parse_args()

    if (args.action == 'create'):
        ec2_region = load_ec2_region(args.region)
        ec2_config = load_ec2_config(args.config, ec2_region)
        log("Region   : {0}".format(ec2_region))
        log("Config   : {0}".format(ec2_config))
        log("Instances: {0}".format(args.instances))
        reservation = run_instances(args.instances, ec2_config, ec2_region)
        dump_hosts_config(ec2_config, reservation, args.hosts)
        dump_ssh_commands(ec2_config, reservation)
    elif (args.action == 'show_defaults'):
        print 
        print "\033[92mConfig\033[0m : {0}".format(json.dumps(DEFAULT_EC2_INSTANCE_CONFIGS,indent=2))
        print "\033[92mInstances\033[0m         : {0}".format(DEFAULT_NUMBER_OF_INSTANCES)
        print "\033[92mSupported regions\033[0m : {0}".format( [ i for i in DEFAULT_EC2_INSTANCE_CONFIGS ] )
        print
    elif (args.action == 'merge_reservations'):
        merge_reservations(args.reservations, args.region)
    else: 
        hosts_config = load_hosts_config(args.hosts)
        invoke_hosts_action(args.action, hosts_config)
        if (args.action == 'terminate'):
            log("Deleting {0} host file.".format(args.hosts))
            os.remove(args.hosts)

if __name__ == '__main__':
    main()


