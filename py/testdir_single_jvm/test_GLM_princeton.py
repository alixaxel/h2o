import unittest, time, sys
sys.path.extend(['.','..','py'])

import h2o, h2o_cmd, h2o_glm, h2o_util

class Basic(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        h2o.build_cloud(1)
        global SYNDATASETS_DIR
        SYNDATASETS_DIR = h2o.make_syn_dir()

    @classmethod
    def tearDownClass(cls):
        h2o.tear_down_cloud()

    def test_GLM_princeton(self):
        # filename, Y, timeoutSecs
        # fix. the ones with comments may want to be a gaussian?
        # these are all counts? using gaussian?
        csvFilenameList = [
            ('cuse.dat', 3, 5), # notUsing
            ('cuse.dat', 4, 5), # using
            ('copen.dat', 5, 5),
            ('housing.raw', 4, 5),
            ]

        trial = 0
        for (csvFilename, Y, timeoutSecs) in csvFilenameList:
            csvPathname1 = h2o.find_file("smalldata/logreg/princeton/" + csvFilename)
            csvPathname2 = SYNDATASETS_DIR + '/' + csvFilename + '_stripped.csv'
            h2o_util.file_strip_trailing_spaces(csvPathname1, csvPathname2)

            kwargs = {'xval': 0, 'case': 'NaN', 'family': 'gaussian', 'link': 'familyDefault', 'Y': Y}
            start = time.time()
            glm = h2o_cmd.runGLM(csvPathname=csvPathname2, key=csvFilename, timeoutSecs=timeoutSecs, **kwargs)
            h2o_glm.simpleCheckGLM(self, glm, None, **kwargs)
            print "glm end (w/check) on ", csvPathname, 'took', time.time() - start, 'seconds'
            trial += 1
            print "\nTrial #", trial

if __name__ == '__main__':
    h2o.unit_main()
